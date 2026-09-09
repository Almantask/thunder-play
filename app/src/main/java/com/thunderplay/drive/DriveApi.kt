package com.thunderplay.drive

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface DriveApi {

    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = FILE_FIELDS,
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = 1000,
        @Query("orderBy") orderBy: String = "name",
    ): FileListResponse

    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = SINGLE_FILE_FIELDS,
    ): DriveFile

    @Streaming
    @GET("drive/v3/files/{fileId}")
    suspend fun download(
        @Path("fileId") fileId: String,
        @Query("alt") alt: String = "media",
    ): ResponseBody

    /**
     * Downloads only the requested byte range, e.g. "bytes=0-4095".
     *
     * A satisfied range answers 206, which is still `isSuccessful`, so the error interceptor lets
     * it through untouched. Drive may ignore the header and answer 200 with the whole file, so the
     * caller must read a bounded number of bytes rather than buffering the body.
     */
    @Streaming
    @GET("drive/v3/files/{fileId}")
    suspend fun downloadRange(
        @Path("fileId") fileId: String,
        @Header("Range") range: String,
        @Query("alt") alt: String = "media",
    ): ResponseBody

    @POST("drive/v3/files")
    suspend fun createFolder(@Body request: CreateFileRequest): DriveFile

    /** Replaces a file's contents. Drive keeps metadata and content on separate endpoints. */
    @PATCH("upload/drive/v3/files/{fileId}")
    suspend fun uploadMedia(
        @Path("fileId") fileId: String,
        @Body body: RequestBody,
        @Query("uploadType") uploadType: String = "media",
    ): DriveFile

    /**
     * Writes Drive-side metadata onto an existing file.
     *
     * This is how generation metadata is kept with a judged track. A real sidecar file is not an
     * option: a service account has no storage quota, so Drive accepts an empty file record and
     * rejects any content with 403 storageQuotaExceeded. Metadata costs no quota, and unlike a
     * separate file it cannot be orphaned - it moves and renames with the track.
     */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun updateMetadata(
        @Path("fileId") fileId: String,
        @Body request: FileMetadataPatch,
        @Query("fields") fields: String = "id,name,description,appProperties",
    ): DriveFile

    /** Moves a file between folders. Drive has no "move"; it is a parent swap. */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun moveFile(
        @Path("fileId") fileId: String,
        @Query("addParents") addParents: String,
        @Query("removeParents") removeParents: String,
        @Query("fields") fields: String = "id,name,parents",
    ): DriveFile

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val SINGLE_FILE_FIELDS =
            "id,name,mimeType,size,md5Checksum,createdTime,modifiedTime,parents"
        const val FILE_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,md5Checksum,createdTime,modifiedTime,parents)"
    }
}
