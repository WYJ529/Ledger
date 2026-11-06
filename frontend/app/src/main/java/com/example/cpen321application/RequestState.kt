package com.example.cpen321application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One UI action at a time. Cancellation propagates and always releases the busy state. */
internal class RequestState(initialError: String? = null) {
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf(initialError)

    suspend fun run(
        onExpired: (() -> Unit)? = null,
        onFailure: suspend (Exception) -> Unit = { error = it.message },
        action: suspend () -> Unit,
    ) {
        if (working) return
        working = true
        error = null
        try {
            handleRequestFailures({ failure ->
                if (failure is ApiException && failure.status == 401 && onExpired != null) onExpired()
                else onFailure(failure)
            }) { action() }
        } finally { working = false }
    }
}
