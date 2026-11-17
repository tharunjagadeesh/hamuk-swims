package com.hamuk.swimpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.container
import kotlinx.coroutines.launch

/**
 * "Update available" card. With [alwaysShow] (Settings) it also shows the current version and a
 * Check now button; otherwise (Home) it only appears when a newer release exists.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, alwaysShow: Boolean = false) {
    val context = LocalContext.current
    val app = context.container
    val updater = app.updater
    val p = LocalPalette.current
    val available by updater.available.collectAsState()
    val progress by updater.progress.collectAsState()
    val message by updater.message.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (!alwaysShow && available == null) return
    GlassCard(modifier, strong = available != null) {
        CardHeader(
            if (available != null) "Update available" else "App updates",
            Icons.Rounded.SystemUpdate, Accent.Time,
            trailing = available?.let { "v${updater.currentVersion} → v${it.versionName}" } ?: "v${updater.currentVersion}",
        )
        val u = available
        if (u != null) {
            if (u.notes.isNotBlank()) Body(u.notes.lines().take(6).joinToString("\n"))
            val prog = progress
            if (prog != null) {
                Box(Modifier.fillMaxWidth().height(10.dp).background(p.track, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(prog.coerceAtLeast(0.03f)).height(10.dp)
                        .background(Brush.horizontalGradient(listOf(Accent.Time, Accent.Swim)), RoundedCornerShape(50)))
                }
                Muted("Downloading… ${(prog * 100).toInt()}%")
            } else {
                PrimaryButton("Update now (${u.sizeBytes / 1024 / 1024 + 1} MB)", {
                    if (!updater.canInstall()) {
                        note = "Allow Hamuk Swims to install updates, then tap Update again."
                        context.startActivity(updater.installPermissionIntent())
                    } else {
                        app.scope.launch { runCatching { updater.downloadAndInstall(u) }.onFailure { note = it.message } }
                    }
                }, Modifier.fillMaxWidth(), color = Accent.Time)
            }
        } else if (!updater.enabled) {
            Muted("Over-the-air updates aren't set up in this build yet.")
        } else {
            Muted("You're on the latest version.")
        }
        if (alwaysShow && updater.enabled) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(if (checking) "Checking…" else "Check now", {
                scope.launch {
                    checking = true
                    note = runCatching { updater.check() }.fold({ if (it == null) "No newer version." else null }, { it.message })
                    checking = false
                }
            })
        }
        (message ?: note)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.textDim) }
    }
}
