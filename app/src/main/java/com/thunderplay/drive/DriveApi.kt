package com.thunderplay.drive

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Body
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

    @POST("drive/v3/files")
    suspend fun createFolder(@Body request: CreateFileRequest): DriveFile

    /** Replaces a file's contents. Drive keeps metadata and content on separate endpoints. */
    @PATCH("upload/drive/v3/files/{fileId}")
    suspend fun uploadMedia(
        @Path("fileId") fileId: String,
        @Body body: RequestBody,
        @Query("uploadType") uploadType: String = "media",
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
