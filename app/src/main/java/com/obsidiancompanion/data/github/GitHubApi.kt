package com.obsidiancompanion.data.github

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * GitHub REST 接口。Phase 3 全部 GET（只读）；Phase 5 增加 Contents write（PUT）与 JSON read。
 * base URL 可注入：默认 https://api.github.com/，本地 E2E 用 mock 服务器覆盖。
 */
interface GitHubApi {

    @Headers("Accept: application/vnd.github+json")
    @GET("user")
    suspend fun getUser(): Response<UserDto>

    @Headers("Accept: application/vnd.github+json")
    @GET("user/repos")
    suspend fun listRepos(
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String,
    ): Response<List<RepoDto>>

    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<RepoDto>

    /** [ref] 可以是 branch 名、tag 或 tree SHA。 */
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}/git/trees/{ref}")
    suspend fun getTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("ref") ref: String,
        @Query("recursive") recursive: Int?,
        @Header("If-None-Match") ifNoneMatch: String?,
    ): Response<TreeResponseDto>

    /** Raw Markdown 正文（不是 GitHub 渲染的 HTML —— §33）。URL 由调用方用 HttpUrl 分段构建（'/' 保留为路径分隔符）。 */
    @Headers("Accept: application/vnd.github.raw")
    @GET
    suspend fun getRawContent(@Url url: okhttp3.HttpUrl): Response<ResponseBody>

    /**
     * JSON 形态的 Contents read（Phase 5 冲突流程）：一次拿回最新 sha + Base64 正文。
     * URL 同样由调用方分段构建。
     */
    @Headers("Accept: application/vnd.github+json")
    @GET
    suspend fun getContent(@Url url: okhttp3.HttpUrl): Response<ContentItemDto>

    /** Contents write（Phase 5）：更新已有文件。body 带 base sha，过期时 GitHub 返回 409。 */
    @Headers("Accept: application/vnd.github+json")
    @PUT
    suspend fun putContent(
        @Url url: okhttp3.HttpUrl,
        @Body body: UpdateFileRequestDto,
    ): Response<UpdateFileResponseDto>
}

object GitHubApiFactory {

    fun createRetrofit(baseUrl: String, tokenProvider: () -> String?): Retrofit {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("X-GitHub-Api-Version", "2022-11-28")
                tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    fun create(baseUrl: String, tokenProvider: () -> String?): GitHubApi =
        createRetrofit(baseUrl, tokenProvider).create(GitHubApi::class.java)
}
