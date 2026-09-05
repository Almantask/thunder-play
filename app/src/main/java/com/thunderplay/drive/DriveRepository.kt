package com.thunderplay.drive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folder-tree operations on top of [DriveApi].
 *
 * Drive is the catalog's source of truth: it is deliberately not mirrored into Firestore, so a
 * track dropped into the folder from any device shows up without the PC being switched on.
 */
@Singleton
class DriveRepository @Inject constructor(
    private val api: DriveApi,
) {

    suspend fun listChildren(folderId: String): List<DriveFile> {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val page = api.listFiles(
                query = childrenQuery(folderId),
                pageToken = pageToken,
            )
            all += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return all
    }

    /** Fetches current metadata, including the authoritative parent list. */
    suspend fun fileById(fileId: String): DriveFile = api.getFile(fileId)

    suspend fun findChildFolder(parentId: String, name: String): DriveFile? =
        api.listFiles(query = namedChildQuery(parentId, name, FOLDER_MIME)).files.firstOrNull()

    /**
     * Finds a folder by name anywhere the caller can see it.
     *
     * A service account's own Drive is empty, so this effectively searches what has been shared
     * with it - which is how the library root is discovered without hardcoding an id.
     */
    suspend fun findFolderByName(name: String): DriveFile? = api.listFiles(
        query = "name=${quoted(name)} and mimeType=${quoted(FOLDER_MIME)} and trashed=false",
    ).files.firstOrNull()

    /** Returns the named child folder, creating it if it does not exist yet. */
    suspend fun ensureChildFolder(parentId: String, name: String): DriveFile =
        findChildFolder(parentId, name) ?: api.createFolder(
            CreateFileRequest(name = name, parents = listOf(parentId)),
        )

    /**
     * Walks [rootFolderId] breadth-first and returns every non-folder descendant with its path
     * relative to the root. The library is ~185 files across ~20 folders, so a full re-list is
     * cheaper to build and reason about than Changes-API page-token bookkeeping.
     */
    suspend fun walk(rootFolderId: String): List<DriveEntry> {
        val results = mutableListOf<DriveEntry>()
        val seenFolders = mutableSetOf(rootFolderId)
        var frontier = listOf(rootFolderId to "")

        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, String>>()
            for ((folderId, prefix) in frontier) {
                for (child in listChildren(folderId)) {
                    val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (child.isFolder) {
                        // Guard against shortcut loops: a folder reachable twice is walked once.
                        if (seenFolders.add(child.id)) next += child.id to path
                    } else {
                        results += DriveEntry(child, path)
                    }
                }
            }
            frontier = next
        }
        return results
    }

    /** Moves a file into [destinationFolderId], detaching it from every current parent. */
    suspend fun moveTo(file: DriveFile, destinationFolderId: String): DriveFile {
        val currentParents = file.parents.orEmpty().joinToString(",")
        return api.moveFile(
            fileId = file.id,
            addParents = destinationFolderId,
            removeParents = currentParents,
        )
    }

    private fun childrenQuery(folderId: String) =
        "${quoted(folderId)} in parents and trashed=false"

    private fun namedChildQuery(parentId: String, name: String, mimeType: String) =
        "${quoted(parentId)} in parents and name=${quoted(name)} and " +
            "mimeType=${quoted(mimeType)} and trashed=false"

    /**
     * Drive query literals are single-quoted; embedded quotes and backslashes must be escaped
     * or a folder named e.g. "Rock 'n' Roll" produces a malformed query.
     */
    private fun quoted(raw: String): String {
        val out = StringBuilder(raw.length + 2)
        out.append('\'')
        for (c in raw) {
            if (c == '\'' || c == BACKSLASH) out.append(BACKSLASH)
            out.append(c)
        }
        out.append('\'')
        return out.toString()
    }

    private companion object {
        const val BACKSLASH = '\u005C'
    }
}
