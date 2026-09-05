package com.thunderplay.library

/**
 * Splits a library-relative path into the parts the UI groups by.
 *
 * Paths look like "Beast Hunt/III/instrumental-....m4a" or "Custom/Demo/track.m4a"; a file sitting
 * directly under the root has no level. Names are used verbatim - the library's folder names
 * contain some mis-encoded characters, and silently "fixing" them here would stop the path from
 * matching Drive.
 */
data class TrackPath(
    val category: String,
    val level: String?,
    val title: String,
) {
    companion object {
        fun parse(relativePath: String): TrackPath {
            val segments = relativePath.split('/').filter { it.isNotEmpty() }
            val fileName = segments.lastOrNull().orEmpty()
            val title = fileName.substringBeforeLast('.', fileName)
            val folders = segments.dropLast(1)
            return TrackPath(
                category = folders.firstOrNull() ?: UNCATEGORISED,
                level = folders.drop(1).joinToString("/").takeIf { it.isNotEmpty() },
                title = title,
            )
        }

        const val UNCATEGORISED = "Uncategorised"
    }
}
