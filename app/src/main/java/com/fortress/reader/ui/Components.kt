package com.fortress.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fortress.reader.BusyState
import com.fortress.reader.SecurityCheck
import com.fortress.reader.SecurityState
import com.fortress.reader.ui.theme.FRSpacing
import com.fortress.reader.ui.theme.LocalExtendedColors

// ------------------------------------------------------------------
// Status vocabulary (never color alone — always glyph + text label)
// ------------------------------------------------------------------

fun statusGlyph(state: SecurityState): String = when (state) {
    SecurityState.PROTECTED -> "●"
    SecurityState.LIMITED -> "▲"
    SecurityState.NOT_VERIFIED -> "○"
    SecurityState.NON_COMPLIANT -> "■"
}

fun statusLabel(state: SecurityState): String = when (state) {
    SecurityState.PROTECTED -> "PROTECTED"
    SecurityState.LIMITED -> "LIMITED"
    SecurityState.NOT_VERIFIED -> "NOT VERIFIED"
    SecurityState.NON_COMPLIANT -> "NON-COMPLIANT"
}

@Composable
fun statusColor(state: SecurityState): Color = when (state) {
    SecurityState.PROTECTED -> LocalExtendedColors.current.success
    SecurityState.LIMITED -> LocalExtendedColors.current.warning
    SecurityState.NOT_VERIFIED -> MaterialTheme.colorScheme.onSurfaceVariant
    SecurityState.NON_COMPLIANT -> MaterialTheme.colorScheme.error
}

// ------------------------------------------------------------------
// Buttons — rectangular, 48 dp minimum height, uppercase labels
// ------------------------------------------------------------------

@Composable
fun FRPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = FRSpacing.touchMin),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            disabledContainerColor = LocalExtendedColors.current.surfaceAlt,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FRSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = FRSpacing.touchMin),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.secondary else LocalExtendedColors.current.divider,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FRDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = FRSpacing.touchMin),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = LocalExtendedColors.current.surfaceAlt,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------
// Indicators and badges
// ------------------------------------------------------------------

@Composable
fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, LocalExtendedColors.current.divider),
    ) {
        Text(
            format.uppercase(),
            modifier = Modifier.padding(horizontal = FRSpacing.xs, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SecurityStatusLabel(state: SecurityState, modifier: Modifier = Modifier) {
    val color = statusColor(state)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(statusGlyph(state), style = MaterialTheme.typography.labelMedium, color = color)
        Text(statusLabel(state), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun FRDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = LocalExtendedColors.current.divider)
}

// ------------------------------------------------------------------
// Rows and panels
// ------------------------------------------------------------------

@Composable
fun DocumentRow(
    title: String,
    meta: String,
    format: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Spacer(Modifier.height(FRSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FRSpacing.sm)) {
            FormatBadge(format)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
            if (statusTag != null) {
                Text(
                    statusTag.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(FRSpacing.sm))
        FRDivider()
    }
}

@Composable
fun TechnicalPanel(
    title: String? = null,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalExtendedColors.current.divider),
    ) {
        Column(Modifier.padding(FRSpacing.md), verticalArrangement = Arrangement.spacedBy(FRSpacing.xs)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(FRSpacing.xxs))
            }
            rows.forEach { (key, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        key.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SecurityRow(check: SecurityCheck, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(FRSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                check.title.uppercase(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            SecurityStatusLabel(check.state)
        }
        Spacer(Modifier.height(FRSpacing.xxs))
        Text(
            check.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FRSpacing.sm))
        FRDivider()
    }
}

// ------------------------------------------------------------------
// Banners (warnings, session notices)
// ------------------------------------------------------------------

@Composable
fun BannerBox(
    title: String,
    lines: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, accent)
            .background(MaterialTheme.colorScheme.surface)
            .padding(FRSpacing.md),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent)
        Spacer(Modifier.height(FRSpacing.xxs))
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ------------------------------------------------------------------
// States: empty / error / busy
// ------------------------------------------------------------------

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(FRSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(1.dp, LocalExtendedColors.current.divider),
        )
        Spacer(Modifier.height(FRSpacing.md))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FRSpacing.xs))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ErrorState(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(FRSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            statusGlyph(SecurityState.NON_COMPLIANT),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(FRSpacing.xs))
        Text("OPERATION FAILED", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(FRSpacing.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FRSpacing.lg))
        FRSecondaryButton("BACK", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
fun BusyView(state: BusyState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(FRSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SectionLabel("OPERATION IN PROGRESS")
        Spacer(Modifier.height(FRSpacing.md))
        Text(state.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(FRSpacing.md))
        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                trackColor = LocalExtendedColors.current.surfaceAlt,
            )
            Spacer(Modifier.height(FRSpacing.xs))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                trackColor = LocalExtendedColors.current.surfaceAlt,
            )
        }
    }
}

// ------------------------------------------------------------------
// Dialogs
// ------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(title.uppercase(), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            if (destructive) {
                FRDestructiveButton(confirmText, onConfirm)
            } else {
                FRPrimaryButton(confirmText, onConfirm)
            }
        },
        dismissButton = {
            FRSecondaryButton("CANCEL", onDismiss)
        },
    )
}

// ------------------------------------------------------------------
// Bottom navigation — red indicator bar marks the selected section
// ------------------------------------------------------------------

enum class NavTab { LIBRARY, VAULT, SECURITY }

@Composable
fun FRNavBar(selected: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            FRDivider()
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                NavItem(NavTab.LIBRARY, "LIBRARY", selected == NavTab.LIBRARY) { onSelect(NavTab.LIBRARY) }
                NavItem(NavTab.VAULT, "VAULT", selected == NavTab.VAULT) { onSelect(NavTab.VAULT) }
                NavItem(NavTab.SECURITY, "SECURITY", selected == NavTab.SECURITY) { onSelect(NavTab.SECURITY) }
            }
        }
    }
}

@Composable
private fun NavItem(tab: NavTab, label: String, selected: Boolean, onSelect: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(24.dp)
                .height(3.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Spacer(Modifier.height(FRSpacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ------------------------------------------------------------------
// Text field colors — ink borders, red cursor
// ------------------------------------------------------------------

@Composable
fun frTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.secondary,
    unfocusedBorderColor = LocalExtendedColors.current.divider,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.secondary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

// ------------------------------------------------------------------
// Small text button used for row actions (DELETE etc.)
// ------------------------------------------------------------------

@Composable
fun RowActionButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = FRSpacing.sm, vertical = FRSpacing.xxs),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
