package com.obsidiancompanion.data.github

import com.obsidiancompanion.model.DomainError
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException

/** Remote 层统一返回值：异常已在边界内消化，ViewModel 只见 Ok / NotModified / Fail。 */
sealed interface GitHubResult<out T> {
    data class Ok<T>(val value: T, val etag: String? = null) : GitHubResult<T>
    data class NotModified(val etag: String?) : GitHubResult<Nothing>
    data class Fail(val error: DomainError, val code: Int? = null) : GitHubResult<Nothing>
}

/** HTTP 状态码 → DomainError（§64/§65-67）。 */
internal fun Response<*>.toFail(): GitHubResult.Fail = when (code()) {
    401 -> GitHubResult.Fail(DomainError.Unauthorized, code())
    403 -> if (headers()["X-RateLimit-Remaining"] == "0") {
        GitHubResult.Fail(DomainError.RateLimited, code())
    } else {
        GitHubResult.Fail(DomainError.Forbidden, code())
    }
    404 -> GitHubResult.Fail(DomainError.NotFound, code())
    409 -> GitHubResult.Fail(DomainError.Conflict, code())
    429 -> GitHubResult.Fail(DomainError.RateLimited, code())
    in 500..599 -> GitHubResult.Fail(DomainError.ServerError, code())
    else -> GitHubResult.Fail(DomainError.Unknown, code())
}

/** 统一异常屏障：IO → NetworkUnavailable；序列化/参数 → MalformedResponse；其余 → Unknown。 */
internal suspend fun <T> runGuarded(block: suspend () -> GitHubResult<T>): GitHubResult<T> = try {
    block()
} catch (e: IOException) {
    GitHubResult.Fail(DomainError.NetworkUnavailable)
} catch (e: SerializationException) {
    GitHubResult.Fail(DomainError.MalformedResponse)
} catch (e: IllegalArgumentException) {
    // kotlinx converter 对非法输入部分抛 IllegalArgumentException
    GitHubResult.Fail(DomainError.MalformedResponse)
} catch (e: Exception) {
    GitHubResult.Fail(DomainError.Unknown)
}
