package com.hamuk.swimpro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.CoachTools
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.ChatMessage

private val suggestions = listOf(
    "How should I breathe when I get tired?",
    "Review my last swim",
    "I can only swim twice a week now",
    "What drills fix a sinking legs feeling?",
)

@Composable
fun ChatScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val chat = app.chat
    val messages by chat.messages.collectAsState(initial = emptyList())
    val busy by chat.busy.collectAsState()
    val error by chat.error.collectAsState()
    val hasKey = remember { app.keys.hasKey() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val visible = messages.filter { m -> chat.text(m).isNotEmpty() || chat.calls(m).isNotEmpty() }
    LaunchedEffect(visible.size, busy, error) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        input = ""
        if (text == "Review my last swim") {
            chat.run(app.scope) { app.swims.allSwims().firstOrNull()?.let { reviewSwim(it) } ?: this.send(text) }
        } else chat.run(app.scope) { this.send(text) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            DetailHeader("Coach", onBack) {
                if (messages.isNotEmpty()) GlassIconButton(Icons.Rounded.DeleteSweep, "Clear chat", { chat.run(app.scope) { clear() } }, size = 42.dp)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!hasKey) item {
                GlassCard(onClick = onOpenSettings) {
                    CardHeader("Connect Gemini", Icons.Rounded.AutoAwesome, Accent.Pace)
                    Body("The coach chat runs on Google Gemini. Get a free API key at aistudio.google.com, then paste it in Settings.")
                    PrimaryButton("Open Settings", onOpenSettings)
                }
            }
            if (visible.isEmpty() && hasKey) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp).appear(0), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Rounded.AutoAwesome, Accent.Pace, 64.dp)
                    Text("Hi! I'm your swim coach.", style = MaterialTheme.typography.titleLarge, color = p.text)
                    Muted("I can see your swims, tests and plan. Ask me anything.")
                    suggestions.forEachIndexed { i, s ->
                        GlassButton(s, { send(s) }, Modifier.fillMaxWidth().appear(i + 1))
                    }
                }
            }
            items(visible, key = { it.id }) { m -> MessageBubble(m) }
            if (busy) item {
                Box(Modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp)) { TypingDots(Accent.Pace) }
            }
            error?.let { e ->
                item {
                    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Accent.Heart, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(e, style = MaterialTheme.typography.bodySmall, color = Accent.Heart)
                    }
                }
            }
        }
        // Floating glass composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)
                .glass(RoundedCornerShape(28.dp), strong = true).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (input.isEmpty()) Text("Ask your coach…", color = p.textDim, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    input, { input = it }, Modifier.fillMaxWidth().heightIn(max = 120.dp),
                    enabled = hasKey,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                    cursorBrush = SolidColor(Accent.Swim),
                )
            }
            val canSend = hasKey && !busy && input.isNotBlank()
            Box(
                Modifier.size(44.dp).pressable(if (canSend) ({ send(input.trim()) }) else null)
                    .background(
                        if (canSend) Brush.linearGradient(listOf(Accent.Swim, Accent.SwimDeep)) else SolidColor(p.track),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (canSend) Color.White else p.textDim, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val chat = app.chat
    val isUser = m.role == "user"
    val text = chat.text(m)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 3 }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
            val shape = if (isUser) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
            Column(
                Modifier.widthIn(max = 320.dp)
                    .then(
                        if (isUser) Modifier.background(Brush.linearGradient(listOf(Accent.Swim, Accent.SwimDeep)), shape)
                        else Modifier.glass(shape),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (text.isNotEmpty()) Text(renderMarkdownLite(text), style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) Color.White else p.text)
                chat.calls(m).forEach { call ->
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strong = true).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.AutoAwesome, Accent.Calories, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Proposed change", style = MaterialTheme.typography.labelLarge, color = Accent.Calories)
                        }
                        Text(CoachTools.describe(call.name, call.args), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                        when (call.state) {
                            "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton("Apply", { chat.run(app.scope) { resolve(m.id, call.partIndex, true) } }, color = Accent.Time)
                                GlassButton("Dismiss", { chat.run(app.scope) { resolve(m.id, call.partIndex, false) } })
                            }
                            "applied" -> Text("Applied ✓  ${call.result ?: ""}", style = MaterialTheme.typography.labelMedium, color = Accent.Time)
                            "failed" -> Text(call.result ?: "Failed", style = MaterialTheme.typography.labelMedium, color = Accent.Heart)
                            else -> Muted("Dismissed")
                        }
                    }
                }
            }
        }
    }
}

/** Strips the most common Markdown so replies read cleanly without a Markdown renderer. */
private fun renderMarkdownLite(text: String): String =
    text.lines().joinToString("\n") { line ->
        line.replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^\\s*[*-]\\s+"), "• ")
            .replace("**", "")
            .replace(Regex("(?<![*])\\*(?![*\\s])(.+?)(?<!\\s)\\*"), "$1")
    }
