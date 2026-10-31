package com.example.cpen321application

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Shared controls in the style of the ESP32 monitor client: flat fills, small corners and a
// thick ink outline. Every button is a bordered bar; there are no bare text buttons.
internal val BarShape: Shape = RoundedCornerShape(6.dp)
internal val PanelShape: Shape = RoundedCornerShape(8.dp)
private val InkWidth = 2.dp

@Composable
internal fun inkBorder(enabled: Boolean = true): BorderStroke =
    BorderStroke(InkWidth, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f))

/** Secondary action: an outlined bar on the surface color. */
@Composable
internal fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = BarShape,
        colors = colors, border = inkBorder(enabled), contentPadding = contentPadding, content = content,
    )
}

/** Main action: a bar filled with the theme's primary color. */
@Composable
internal fun PrimaryBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = BarShape,
        colors = colors, border = inkBorder(enabled), contentPadding = contentPadding, content = content,
    )
}

/** Navigation back: a bar with an arrow before the label. Full-width bars keep the label at the start. */
@Composable
internal fun BackBar(label: String, onClick: () -> Unit, enabled: Boolean = true, fullWidth: Boolean = true) {
    BarButton(onClick = onClick, modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier, enabled = enabled) {
        Text("←", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(t(label))
        if (fullWidth) Spacer(Modifier.weight(1f))
    }
}

/** Icon-only action in a small outlined box, like the ESP32 card icons. */
@Composable
internal fun BarIconButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.material3.OutlinedIconButton(onClick = onClick, enabled = enabled, shape = BarShape,
        border = inkBorder(enabled), content = content)
}

/** Card with the thick ink outline. Clickable when [onClick] is given. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick == null) {
        androidx.compose.material3.Card(modifier = modifier, shape = PanelShape, colors = colors,
            border = inkBorder(), content = content)
    } else {
        androidx.compose.material3.Card(onClick = onClick, modifier = modifier, enabled = enabled, shape = PanelShape,
            colors = colors, border = inkBorder(enabled), content = content)
    }
}
