package com.thunderplay.sync

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.FakeTrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.CreateFileRequest
import com.thunderplay.drive.DriveApi
import com.thunderplay.drive.DriveRepository
import com.thunderplay.drive.FileMetadataPatch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * Covers what the indexer must get right to be run repeatedly: a track is read once, a track that
 * could not be read is read again, and a track whose file changed is re-read.
 */
class MetadataIndexerTest {

    @Test
    fun `stores what the header said`() = runTest {
        val dao = FakeTrackDao(listOf(track("a")))
        val api = FakeApi(heads = mapOf("a" to wav()))

        val result = MetadataIndexer(DriveRepository(api), dao).index()

        val row = dao.rows.getValue("a")
        assertThat(row.prompt).isEqualTo("a low brass bed, ominous")
        assertThat(row.genre).isEqualTo("Instrumental")
        assertThat(row.intensity).isEqualTo("III")
        assertThat(row.instrumentList).containsExactly("low brass", "sub-bass drone").inOrder()
        assertThat(row.durationMs).isEqualTo(40_000)
        assertThat(result.described).isEqualTo(1)
    }

    @Test
    fun `reads the source wav, not the playable file`() = runTest {
        // On the transcoded layout the .m4a carries no tags at all, so reading it would find
        // nothing and then mark the track done for ever.
        val dao = FakeTrackDao(listOf(track("m4a").copy(sourceWavDriveId = "wav")))
        val api = FakeApi(heads = mapOf("wav" to wav()))

        MetadataIndexer(DriveRepository(api), dao).index()

        assertThat(api.requested).containsExactly("wav")
        assertThat(dao.rows.getValue("m4a").prompt).isEqualTo("a low brass bed, ominous")
    }

    @Test
    fun `a file with nothing to say is not read a second time`() = runTest {
        val dao = FakeTrackDao(listOf(track("a")))
        val api = FakeApi(heads = mapOf("a" to NOT_A_WAV))

        val indexer = MetadataIndexer(DriveRepository(api), dao)
        indexer.index()
        val second = indexer.index()

        assertThat(dao.rows.getValue("a").metadataReadAt).isNotNull()
        assertThat(second.attempted).isEqualTo(0)
        assertThat(api.requested).containsExactly("a")
    }

    @Test
    fun `a failed read stays pending`() = runTest {
        // The usual cause is a dropped connection, and giving up on the file permanently would
        // leave it blank until its checksum happened to change.
        val dao = FakeTrackDao(listOf(track("a")))
        val api = FakeApi(heads = emptyMap())

        val result = MetadataIndexer(DriveRepository(api), dao).index()

        assertThat(result.failed).isEqualTo(1)
        assertThat(dao.rows.getValue("a").metadataReadAt).isNull()
        assertThat(dao.needingMetadata(10)).hasSize(1)
    }

    @Test
    fun `a regenerated file is read again`() = runTest {
        val dao = FakeTrackDao(listOf(track("a")))
        val api = FakeApi(heads = mapOf("a" to wav()))
        val indexer = MetadataIndexer(DriveRepository(api), dao)
        indexer.index()

        // Same Drive id, new content: the prompt that produced it has changed with it.
        dao.rows["a"] = dao.rows.getValue("a").copy(md5Checksum = "second")
        api.heads["a"] = wav(prompt = "a bright fanfare")
        indexer.index()

        assertThat(dao.rows.getValue("a").prompt).isEqualTo("a bright fanfare")
    }

    // --- helpers ---

    private fun track(id: String) = TrackEntity(
        driveId = id,
        title = id,
        relativePath = "Boss/III/$id.wav",
        category = "Boss",
        level = "III",
        sizeBytes = null,
        md5Checksum = "first",
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = id,
    )

    /**
     * Only the range endpoint is in scope; every other call should fail loudly rather than
     * quietly returning something the indexer might act on.
     */
    private class FakeApi(heads: Map<String, ByteArray>) : DriveApi {
        val heads = heads.toMutableMap()
        val requested = mutableListOf<String>()

        override suspend fun downloadRange(
            fileId: String,
            range: String,
            alt: String,
        ): ResponseBody {
            requested += fileId
            val head = heads[fileId] ?: error("404 for $fileId")
            return head.toResponseBody("audio/wav".toMediaType())
        }

        override suspend fun listFiles(
            query: String,
            fields: String,
            pageToken: String?,
            pageSize: Int,
            orderBy: String,
        ) = error("not used")

        override suspend fun getFile(fileId: String, fields: String) = error("not used")

        override suspend fun download(fileId: String, alt: String) = error("not used")

        override suspend fun createFolder(request: CreateFileRequest) = error("not used")

        override suspend fun uploadMedia(
            fileId: String,
            body: RequestBody,
            uploadType: String,
        ) = error("not used")

        override suspend fun updateMetadata(
            fileId: String,
            request: FileMetadataPatch,
            fields: String,
        ) = error("not used")

        override suspend fun moveFile(
            fileId: String,
            addParents: String,
            removeParents: String,
            fields: String,
        ) = error("not used")
    }

    private companion object {
        val NOT_A_WAV = "<!doctype html>".toByteArray()

        fun wav(prompt: String = "a low brass bed, ominous"): ByteArray {
            fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v ushr 24) and 0xFF).toByte(),
            )

            fun field(tag: String, text: String): ByteArray {
                val payload = text.toByteArray(Charsets.UTF_8) + 0
                val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
                return ascii(tag) + le32(payload.size) + payload + pad
            }

            var info = ascii("INFO") +
                field("INAM", prompt) +
                field("IGNR", "Instrumental") +
                field("IART", "III") +
                field("IKEY", "low brass;sub-bass drone")
            if (info.size % 2 == 1) info += 0

            val fmtBody = ByteArray(8) + le32(176_400) + ByteArray(4)
            val body = ascii("WAVE") +
                ascii("fmt ") + le32(fmtBody.size) + fmtBody +
                ascii("LIST") + le32(info.size) + info +
                ascii("data") + le32(7_056_000)
            return ascii("RIFF") + le32(body.size) + body
        }
    }
}
