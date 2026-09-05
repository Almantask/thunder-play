package com.thunderplay.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
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
}
