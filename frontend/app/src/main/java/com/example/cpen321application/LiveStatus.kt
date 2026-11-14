package com.example.cpen321application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// A refresh this short finishes before its label would appear, so quick live updates do not blink.
private const val LIVE_STATUS_DELAY_MS = 400L

/**
 * A live page's loading or refresh problem, shown in the top bar beside the back button. The bar keeps its
 * height either way, so the page below never moves when a refresh starts or ends. [problem] wins over [working].
 */
@Composable
internal fun LiveStatusLine(working: String?, problem: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null,
                            retryLabel: String = "Retry") {
    var showWorking by remember { mutableStateOf(false) }
    LaunchedEffect(working != null) {
        showWorking = false
        if (working != null) {
            delay(LIVE_STATUS_DELAY_MS)
            showWorking = true
        }
    }
    Row(modifier.testTag("live-status"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            problem != null -> {
                Text(t(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (onRetry != null) BarButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(t(retryLabel), style = MaterialTheme.typography.labelMedium)
                }
            }
            working != null && showWorking -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(t(working), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}
