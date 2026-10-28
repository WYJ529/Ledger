package com.example.cpen321application

import org.json.JSONException
import java.io.IOException

internal sealed interface SplitRequestResult<out T> {
    data class Success<T>(val value: T) : SplitRequestResult<T>
    data class Failure(val error: Exception) : SplitRequestResult<Nothing>
}

/** Only expected transport/API/JSON failures become UI state; cancellation and defects propagate. */
internal suspend fun <T> splitRequest(block: suspend () -> T): SplitRequestResult<T> = try {
    SplitRequestResult.Success(block())
} catch (error: ApiException) {
    SplitRequestResult.Failure(error)
} catch (error: IOException) {
    SplitRequestResult.Failure(error)
} catch (error: JSONException) {
    SplitRequestResult.Failure(error)
}

/** A failed refresh after a confirmed write must never turn that write into an uncertain retry. */
internal suspend fun <T> runSplitMutation(
    send: suspend () -> T,
    accepted: (T) -> Unit,
    rejected: (ApiException) -> Unit,
    uncertain: (Exception) -> Unit,
    refresh: suspend () -> Unit,
    refreshFailed: (Exception) -> Unit,
) {
    when (val result = splitRequest(send)) {
        is SplitRequestResult.Success -> accepted(result.value)
        is SplitRequestResult.Failure -> {
            val error = result.error
            if (error !is ApiException || error.status !in 400..499 || error.status == 401) {
                uncertain(error)
                return
            }
            rejected(error)
        }
    }
    when (val snapshot = splitRequest(refresh)) {
        is SplitRequestResult.Failure -> refreshFailed(snapshot.error)
        is SplitRequestResult.Success -> Unit
    }
}

/** Keep the original key until an accepted action's canonical state is installed successfully. */
internal suspend fun recoverPendingSplit(
    lookup: suspend () -> SplitOperation,
    refresh: suspend () -> Unit,
    clear: () -> Unit,
): Boolean {
    val operation = try {
        lookup()
    } catch (error: ApiException) {
        if (error.status == 404) return false
        throw error
    }
    if (operation.state != "complete") return false
    refresh()
    clear()
    return true
}
