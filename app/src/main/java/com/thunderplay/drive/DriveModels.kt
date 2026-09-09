package com.thunderplay.drive

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

const val FOLDER_MIME = "application/vnd.google-apps.folder"

@JsonClass(generateAdapter = true)
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long? = null,
    val md5Checksum: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val parents: List<String>? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
}

@JsonClass(generateAdapter = true)
data class FileListResponse(
    val files: List<DriveFile> = emptyList(),
    @Json(name = "nextPageToken") val nextPageToken: String? = null,
)

/** A file found by walking a folder tree, with its path relative to the walk root. */
data class DriveEntry(
    val file: DriveFile,
    /** Slash-separated, excluding the root folder itself, e.g. "Beast Hunt/III/track.m4a". */
    val relativePath: String,
) {
    /** Path without the file extension, used to join the wav and m4a trees. */
    val pathKey: String get() = relativePath.substringBeforeLast('.', relativePath)

    /**
     * Whether this is actually audio.
     *
     * The folders hold more than tracks - the transcode script keeps its manifest in
     * music-mobile/, and Drive itself can leave stray files around - and anything that is not
     * audio would otherwise show up as an unplayable entry in the library.
     */
    val isAudio: Boolean
        get() {
            val extension = file.name.substringAfterLast('.', "").lowercase()
            return extension in AUDIO_EXTENSIONS
        }

    companion object {
        val AUDIO_EXTENSIONS = setOf("m4a", "mp4", "aac", "wav", "mp3", "ogg", "opus", "flac")
    }
}

@JsonClass(generateAdapter = true)
data class CreateFileRequest(
    val name: String,
    val parents: List<String>,
    val mimeType: String = FOLDER_MIME,
)

/**
 * A partial metadata write. Moshi omits nulls, so an unset field leaves Drive's value alone rather
 * than blanking it.
 *
 * Drive caps [description] at 4096 characters, and each appProperties key/value pair at 124 bytes
 * total - which a 120-character prompt can exceed on its own once encoded. So prose goes in the
 * description and only short structured values go in [appProperties].
 */
@JsonClass(generateAdapter = true)
data class FileMetadataPatch(
    val description: String? = null,
    val appProperties: Map<String, String>? = null,
)

/** One folder's worth of files from a streaming walk, with running progress. */
data class WalkBatch(
    val files: List<DriveEntry>,
    val foldersScanned: Int,
)
