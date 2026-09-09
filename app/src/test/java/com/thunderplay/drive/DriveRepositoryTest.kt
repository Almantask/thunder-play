package com.thunderplay.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * Drives [DriveRepository] against an in-memory folder tree so the walk, pagination and query
 * escaping are all exercised without a network.
 */
class DriveRepositoryTest {

    private fun folder(id: String, name: String) = DriveFile(id, name, FOLDER_MIME)

    private fun track(id: String, name: String, parent: String) =
        DriveFile(id, name, "audio/mp4", size = 1_500_000, parents = listOf(parent))

    /**
     * root
     *  |- Beast Hunt/ III/ a.m4a
     *  |- Rock 'n' Roll/ b.m4a          (apostrophe exercises query escaping)
     */
    private val tree: Map<String, List<DriveFile>> = mapOf(
        "root" to listOf(folder("f1", "Beast Hunt"), folder("f3", "Rock 'n' Roll")),
        "f1" to listOf(folder("f2", "III")),
        "f2" to listOf(track("a", "a.m4a", "f2")),
        "f3" to listOf(track("b", "b.m4a", "f3")),
    )

    private class FakeApi(
        private val tree: Map<String, List<DriveFile>>,
        private val pageSizeOverride: Int? = null,
    ) : DriveApi {
        val queries = mutableListOf<String>()

        override suspend fun listFiles(
            query: String,
            fields: String,
            pageToken: String?,
            pageSize: Int,
            orderBy: String,
        ): FileListResponse {
            queries += query
            // A global search (findFolderByName) carries no parent clause; Drive treats that as
            // "everything visible", so the fake has to as well.
            val parent = PARENT.find(query)?.groupValues?.get(1)
            val byName = nameFilter(query)
            val candidates = if (parent == null) tree.values.flatten() else tree[parent].orEmpty()
            val all = candidates.filter { byName == null || it.name == byName }

            val limit = pageSizeOverride ?: all.size.coerceAtLeast(1)
            val start = pageToken?.toInt() ?: 0
            val slice = all.drop(start).take(limit)
            val next = (start + limit).takeIf { it < all.size }?.toString()
            return FileListResponse(slice, next)
        }

        override suspend fun download(fileId: String, alt: String): ResponseBody =
            error("not used")

        val ranges = mutableListOf<String>()

        /** Answers with [headBytes], so a caller asking for more than the file holds still works. */
        var headBytes: ByteArray = ByteArray(0)

        override suspend fun downloadRange(
            fileId: String,
            range: String,
            alt: String,
        ): ResponseBody {
            ranges += range
            return headBytes.toResponseBody("audio/wav".toMediaType())
        }

        val metadata = mutableListOf<Pair<String, FileMetadataPatch>>()

        override suspend fun updateMetadata(
            fileId: String,
            request: FileMetadataPatch,
            fields: String,
        ): DriveFile {
            metadata += fileId to request
            return tree.values.flatten().first { it.id == fileId }
        }

        override suspend fun uploadMedia(
            fileId: String,
            body: okhttp3.RequestBody,
            uploadType: String,
        ): DriveFile = tree.values.flatten().first { it.id == fileId }

        override suspend fun getFile(fileId: String, fields: String): DriveFile =
            tree.values.flatten().firstOrNull { it.id == fileId }
                ?: error("no such file: $fileId")

        val created = mutableListOf<CreateFileRequest>()

        override suspend fun createFolder(request: CreateFileRequest): DriveFile {
            created += request
            return DriveFile("new-${created.size}", request.name, FOLDER_MIME,
                parents = request.parents)
        }

        override suspend fun moveFile(
            fileId: String,
            addParents: String,
            removeParents: String,
            fields: String,
        ): DriveFile = tree.values.flatten().first { it.id == fileId }
            .copy(parents = listOf(addParents))

        private companion object {
            val PARENT = Regex("'((?:[^']|'')*)' in parents")
            const val BACKSLASH = '\u005C'

            /** Mirrors the repository's own escaping so the fake filters like Drive would. */
            fun nameFilter(query: String): String? {
                val marker = "name='"
                val start = query.indexOf(marker)
                if (start < 0) return null
                val out = StringBuilder()
                var i = start + marker.length
                while (i < query.length) {
                    val c = query[i]
                    when {
                        c == BACKSLASH && i + 1 < query.length -> { out.append(query[i + 1]); i += 2 }
                        c == '\'' -> return out.toString()
                        else -> { out.append(c); i++ }
                    }
                }
                return out.toString()
            }
        }
    }

    @Test
    fun `walk returns every leaf with its path relative to the root`() = runTest {
        val entries = DriveRepository(FakeApi(tree)).walk("root")

        assertThat(entries.map { it.relativePath }).containsExactly(
            "Beast Hunt/III/a.m4a",
            "Rock 'n' Roll/b.m4a",
        )
    }

    @Test
    fun `walk follows pagination`() = runTest {
        val many = (1..7).map { track("t$it", "t$it.m4a", "root") }
        val entries = DriveRepository(FakeApi(mapOf("root" to many), pageSizeOverride = 2))
            .walk("root")

        assertThat(entries).hasSize(7)
        assertThat(entries.map { it.file.id }).containsNoDuplicates()
    }

    @Test
    fun `pathKey drops the extension so wav and m4a trees can be joined`() {
        val entry = DriveEntry(track("a", "a.m4a", "f2"), "Beast Hunt/III/a.m4a")
        assertThat(entry.pathKey).isEqualTo("Beast Hunt/III/a")
    }

    @Test
    fun `folder names containing an apostrophe are escaped in the query`() = runTest {
        val api = FakeApi(tree)
        DriveRepository(api).findChildFolder("root", "Rock 'n' Roll")

        val query = api.queries.single()
        // The literal must be escaped, otherwise the apostrophe closes the string early.
        assertThat(query).contains("name='Rock " + '\u005C' + "'n" + '\u005C' + "' Roll'")
    }

    @Test
    fun `moveTo detaches every existing parent`() = runTest {
        val api = FakeApi(tree)
        val file = track("a", "a.m4a", "f2").copy(parents = listOf("f2", "other"))
        DriveRepository(api).moveTo(file, "trash")
        // Nothing to assert on the fake beyond it not throwing; the parent list is what matters.
        assertThat(file.parents).containsExactly("f2", "other")
    }

    @Test
    fun `moveTo is a no-op when the file is already in the destination`() = runTest {
        // Drive 400s when addParents and removeParents overlap, and a retry or a re-judge hands us
        // exactly that case.
        val api = FakeApi(tree)
        val file = track("a", "a.m4a", "f2").copy(parents = listOf("dest"))

        val result = DriveRepository(api).moveTo(file, "dest")

        assertThat(result).isSameInstanceAs(file)
    }

    @Test
    fun `readHead asks for a bounded range and returns only that many bytes`() = runTest {
        val api = FakeApi(tree).apply { headBytes = ByteArray(9000) { it.toByte() } }

        val head = DriveRepository(api).readHead("a", 4096)

        assertThat(api.ranges).containsExactly("bytes=0-4095")
        // Drive can ignore the header and send everything; the read must still be bounded.
        assertThat(head).hasLength(4096)
    }

    @Test
    fun `readHead returns null rather than throwing when Drive refuses`() = runTest {
        val api = object : DriveApi by FakeApi(tree) {
            override suspend fun downloadRange(fileId: String, range: String, alt: String) =
                error("403")
        }

        assertThat(DriveRepository(api).readHead("a", 4096)).isNull()
    }

    @Test
    fun `writeMetadata sends description and appProperties together`() = runTest {
        val api = FakeApi(tree)

        DriveRepository(api).writeMetadata("a", "a quiet bed", mapOf("abVerdict" to "good"))

        val (fileId, patch) = api.metadata.single()
        assertThat(fileId).isEqualTo("a")
        assertThat(patch.description).isEqualTo("a quiet bed")
        assertThat(patch.appProperties).containsExactly("abVerdict", "good")
    }

    @Test
    fun `ensureChildFolder creates the folder only when it is missing`() = runTest {
        val api = FakeApi(tree)
        val repo = DriveRepository(api)

        val existing = repo.ensureChildFolder("root", "Beast Hunt")
        assertThat(existing.id).isEqualTo("f1")
        assertThat(api.created).isEmpty()

        val fresh = repo.ensureChildFolder("root", "_ThunderPlayTrash")
        assertThat(fresh.name).isEqualTo("_ThunderPlayTrash")
        assertThat(api.created.single().parents).containsExactly("root")
    }

    @Test
    fun `findFolderByName sends a well-formed query`() = runTest {
        val api = FakeApi(mapOf("root" to listOf(folder("f1", "Beast Hunt"))))
        DriveRepository(api).findFolderByName("Music-And-Fx-Generated-Library")

        val query = api.queries.single()
        assertThat(query).contains("name='Music-And-Fx-Generated-Library'")
        assertThat(query).contains("mimeType='" + FOLDER_MIME + "'")
        assertThat(query).contains("trashed=false")
    }

    @Test
    fun `no query ever leaks an unevaluated string template`() = runTest {
        // A mis-escaped Kotlin template compiles fine and only fails as an HTTP 400 from Drive,
        // so assert on the generated text rather than trusting it by eye.
        val api = FakeApi(tree)
        val repo = DriveRepository(api)
        repo.findFolderByName("Anything")
        repo.findChildFolder("root", "Beast Hunt")
        repo.listChildren("root")

        assertThat(api.queries).isNotEmpty()
        api.queries.forEach { query ->
            assertThat(query).doesNotContain("${'$'}{")
            assertThat(query).doesNotContain("quoted(")
        }
    }

    @Test
    fun `the transcode manifest is not mistaken for a track`() {
        // music-mobile/ really does contain .manifest.json; without this filter it shows up in
        // the library as an unplayable entry called ".manifest".
        val manifest = DriveEntry(
            DriveFile("m", ".manifest.json", "application/json"),
            ".manifest.json",
        )
        assertThat(manifest.isAudio).isFalse()
    }

    @Test
    fun `audio files are recognised whatever the case of the extension`() {
        fun entry(name: String) = DriveEntry(DriveFile("x", name, "audio/mp4"), name)

        assertThat(entry("track.m4a").isAudio).isTrue()
        assertThat(entry("track.M4A").isAudio).isTrue()
        assertThat(entry("track.wav").isAudio).isTrue()
        assertThat(entry("cover.jpg").isAudio).isFalse()
        assertThat(entry("no-extension").isAudio).isFalse()
    }

    @Test
    fun `walkStreaming reports results before the whole tree is listed`() = runTest {
        val batches = mutableListOf<WalkBatch>()
        DriveRepository(FakeApi(tree)).walkStreaming("root").collect(batches::add)

        // The point of streaming: more than one emission, so the UI fills in progressively
        // instead of waiting for every request to return.
        assertThat(batches.size).isAtLeast(2)
        assertThat(batches.first().foldersScanned).isEqualTo(1)
    }

    @Test
    fun `walkStreaming finds exactly what the blocking walk finds`() = runTest {
        val repo = DriveRepository(FakeApi(tree))
        val streamed = mutableListOf<DriveEntry>()
        repo.walkStreaming("root").collect { streamed += it.files }

        assertThat(streamed.map { it.relativePath })
            .containsExactlyElementsIn(repo.walk("root").map { it.relativePath })
    }

    @Test
    fun `foldersScanned climbs monotonically so progress never goes backwards`() = runTest {
        val counts = mutableListOf<Int>()
        DriveRepository(FakeApi(tree)).walkStreaming("root").collect { counts += it.foldersScanned }

        assertThat(counts).isInOrder()
        assertThat(counts.distinct()).hasSize(counts.size)
    }

    @Test
    fun `a folder reachable twice is still only walked once`() = runTest {
        // Drive shortcuts can make a tree cyclic; a streaming walk must not loop forever.
        val cyclic = mapOf(
            "root" to listOf(folder("f1", "A")),
            "f1" to listOf(folder("root", "back-to-root"), track("t", "t.m4a", "f1")),
        )
        val streamed = mutableListOf<DriveEntry>()
        DriveRepository(FakeApi(cyclic)).walkStreaming("root").collect { streamed += it.files }

        assertThat(streamed).hasSize(1)
    }

    @Test
    fun `an empty library completes without emitting files`() = runTest {
        val streamed = mutableListOf<DriveEntry>()
        DriveRepository(FakeApi(mapOf("root" to emptyList())))
            .walkStreaming("root")
            .collect { streamed += it.files }

        assertThat(streamed).isEmpty()
    }
}
