package com.thunderplay.drive

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
     *
     * Prefer [walkStreaming] for anything user-facing: this only returns once the entire tree has
     * been listed, which is a long time to show nothing.
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

    /**
     * Walks the tree and emits each folder's files as soon as they arrive.
     *
     * The whole-tree version means nothing appears until every request has returned - around a
     * minute for this library - so the app looks frozen even though results are streaming in.
     * Emitting per folder lets the catalog fill in progressively.
     *
     * Siblings are listed concurrently because the cost here is round-trip latency, not
     * bandwidth. The limit keeps a wide folder from opening dozens of sockets at once.
     */
    fun walkStreaming(rootFolderId: String, concurrency: Int = 6): Flow<WalkBatch> = flow {
        val seenFolders = mutableSetOf(rootFolderId)
        val gate = Semaphore(concurrency)
        var frontier = listOf(rootFolderId to "")
        var foldersScanned = 0

        while (frontier.isNotEmpty()) {
            val listed = coroutineScope {
                frontier.map { (folderId, prefix) ->
                    async { prefix to gate.withPermit { listChildren(folderId) } }
                }.awaitAll()
            }

            val next = mutableListOf<Pair<String, String>>()
            for ((prefix, children) in listed) {
                val files = mutableListOf<DriveEntry>()
                for (child in children) {
                    val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (child.isFolder) {
                        // Guard against shortcut loops: a folder reachable twice is walked once.
                        if (seenFolders.add(child.id)) next += child.id to path
                    } else {
                        files += DriveEntry(child, path)
                    }
                }
                foldersScanned++
                // Emit even an empty folder, so progress reflects work done rather than stalling
                // on a branch that happens to hold only subfolders.
                emit(WalkBatch(files, foldersScanned))
            }
            frontier = next
        }
    }

    /** Moves a file into [destinationFolderId], detaching it from every current parent. */
    suspend fun moveTo(file: DriveFile, destinationFolderId: String): DriveFile {
        // Drive rejects a patch whose addParents and removeParents overlap, so a file already in
        // the destination would 400. Retries and re-judgements both hand us exactly that, and they
        // should be no-ops rather than errors.
        if (file.parents == listOf(destinationFolderId)) return file
        val currentParents = file.parents.orEmpty().joinToString(",")
        return api.moveFile(
            fileId = file.id,
            addParents = destinationFolderId,
            removeParents = currentParents,
        )
    }

    /**
     * Reads the first [byteCount] bytes of a file, or null if it cannot be read.
     *
     * Enough for a WAV's header chunks, which is the point: the prompt sits a few hundred bytes
     * in, and downloading a 40 MB file to read one sentence would be absurd. Drive may ignore the
     * Range header and answer with the whole file, so the body is read a bounded number of bytes
     * at a time and never buffered whole.
     */
    suspend fun readHead(fileId: String, byteCount: Int): ByteArray? = try {
        api.downloadRange(fileId, range = "bytes=0-${byteCount - 1}").use { body ->
            val source = body.source()
            // request() returns false once the stream ends, which is fine - a file shorter than
            // byteCount simply yields everything it has.
            source.request(byteCount.toLong())
            val available = minOf(source.buffer.size, byteCount.toLong())
            source.buffer.readByteArray(available)
        }
    } catch (e: Exception) {
        null
    }

    /** Writes Drive-side metadata. Costs no storage quota, unlike writing file content. */
    suspend fun writeMetadata(
        fileId: String,
        description: String? = null,
        appProperties: Map<String, String>? = null,
    ): DriveFile = api.updateMetadata(
        fileId = fileId,
        request = FileMetadataPatch(description = description, appProperties = appProperties),
    )

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
