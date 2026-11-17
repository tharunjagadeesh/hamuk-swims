package com.hamuk.swimpro.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------------------------
// Palette: Samsung Health–style metric colours on an Apple-style glass/water backdrop.
// ---------------------------------------------------------------------------------------------

/** Metric accent colours, the same in light and dark (like Samsung Health's category colours). */
object Accent {
    val Swim = Color(0xFF1EA7FD)
    val SwimDeep = Color(0xFF3A6BFF)
    val Time = Color(0xFF2FD27A)
    val Heart = Color(0xFFFF4D6D)
    val Calories = Color(0xFFFF9F0A)
    val Pace = Color(0xFF8E7CFF)
    val Swolf = Color(0xFF22D3C5)
    val Gold = Color(0xFFFFC53D)

    val zone = mapOf(
        0 to Color(0xFF9AA7B8),
        1 to Color(0xFF7FB3FF),
        2 to Color(0xFF2FD27A),
        3 to Color(0xFFFFD43B),
        4 to Color(0xFFFF9F0A),
        5 to Color(0xFFFF4D6D),
        6 to Color(0xFFB57BFF),
    )
}

@Immutable
data class Palette(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blobs: List<Color>,
    val glassTint: Color,
    val glassStrong: Color,
    val borderHi: Color,
    val borderLo: Color,
    val text: Color,
    val textDim: Color,
    val track: Color,
)

private val DarkPalette = Palette(
    dark = true,
    bgTop = Color(0xFF041019),
    bgBottom = Color(0xFF0A2238),
    blobs = listOf(Color(0xFF0096D6), Color(0xFF6A4CFF), Color(0xFF00D1B2), Color(0xFFFF4D8D)),
    glassTint = Color.White.copy(alpha = 0.07f),
    glassStrong = Color.White.copy(alpha = 0.14f),
    borderHi = Color.White.copy(alpha = 0.38f),
    borderLo = Color.White.copy(alpha = 0.04f),
    text = Color.White,
    textDim = Color.White.copy(alpha = 0.62f),
    track = Color.White.copy(alpha = 0.10f),
)

private val LightPalette = Palette(
    dark = false,
    bgTop = Color(0xFFEAF6FF),
    bgBottom = Color(0xFFD6E8FF),
    blobs = listOf(Color(0xFF5CCBFF), Color(0xFFB29BFF), Color(0xFF7DF0D8), Color(0xFFFFB3A1)),
    glassTint = Color.White.copy(alpha = 0.42f),
    glassStrong = Color.White.copy(alpha = 0.62f),
    borderHi = Color.White.copy(alpha = 0.95f),
    borderLo = Color.White.copy(alpha = 0.25f),
    text = Color(0xFF0B1B2B),
    textDim = Color(0xFF0B1B2B).copy(alpha = 0.58f),
    track = Color(0xFF0B1B2B).copy(alpha = 0.08f),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun SwimProTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = Accent.Swim, onPrimary = Color.White, secondary = Accent.Time, tertiary = Accent.Calories,
            background = p.bgTop, surface = p.bgTop, onSurface = p.text, onBackground = p.text,
            onSurfaceVariant = p.textDim, surfaceContainer = Color(0xFF10263A), surfaceContainerHigh = Color(0xFF16304A),
            outline = p.borderHi, outlineVariant = p.track, error = Accent.Heart,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0A84FF), onPrimary = Color.White, secondary = Accent.Time, tertiary = Accent.Calories,
            background = p.bgTop, surface = p.bgTop, onSurface = p.text, onBackground = p.text,
            onSurfaceVariant = p.textDim, surfaceContainer = Color(0xFFF2F8FF), surfaceContainerHigh = Color.White,
            outline = Color(0xFF9DB4CC), outlineVariant = p.track, error = Color(0xFFE5304F),
        )
    }
    val base = MaterialTheme.typography
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Backdrop: slow-moving light blobs over a deep water gradient. This is what the glass blurs.
// ---------------------------------------------------------------------------------------------

@Composable
fun WaterBackground(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val t by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "t",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
        val w = size.width
        val h = size.height
        val blobs = listOf(
            Triple(Offset(w * (0.15f + 0.25f * t), h * (0.12f + 0.06f * t)), w * 0.75f, p.blobs[0].copy(alpha = if (p.dark) 0.55f else 0.65f)),
            Triple(Offset(w * (0.95f - 0.3f * t), h * (0.35f + 0.1f * t)), w * 0.7f, p.blobs[1].copy(alpha = if (p.dark) 0.42f else 0.5f)),
            Triple(Offset(w * (0.2f + 0.2f * (1 - t)), h * (0.72f - 0.08f * t)), w * 0.8f, p.blobs[2].copy(alpha = if (p.dark) 0.30f else 0.45f)),
            Triple(Offset(w * (0.85f - 0.2f * t), h * (0.95f - 0.1f * t)), w * 0.6f, p.blobs[3].copy(alpha = if (p.dark) 0.22f else 0.35f)),
        )
        blobs.forEach { (c, r, col) ->
            drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), center = c, radius = r), radius = r, center = c)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glass surfaces
// ---------------------------------------------------------------------------------------------

@Composable
fun glassStyle(strong: Boolean = false): HazeStyle {
    val p = LocalPalette.current
    return HazeStyle(
        backgroundColor = p.bgBottom,
        tint = HazeTint(if (strong) p.glassStrong else p.glassTint),
        blurRadius = if (strong) 30.dp else 24.dp,
        noiseFactor = 0.06f,
    )
}

/** Frosted glass: backdrop blur + tint, a specular top sheen and a light-catching rim. */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = composed {
    val p = LocalPalette.current
    val haze = LocalHaze.current
    val style = glassStyle(strong)
    this
        .clip(shape)
        .then(if (haze != null) Modifier.hazeEffect(haze, style) else Modifier.background(if (strong) p.glassStrong else p.glassTint))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.dark) 0.06f else 0.18f), Color.Transparent)))
        .border(1.dp, Brush.linearGradient(listOf(p.borderHi, p.borderLo, p.borderHi.copy(alpha = p.borderHi.alpha * 0.4f))), shape)
}

/** Springy press feedback, iOS style (no ripple). */
fun Modifier.pressable(onClick: (() -> Unit)?): Modifier = composed {
    if (onClick == null) return@composed this
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Staggered entrance: fades, lifts and settles with a spring. */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L * index.coerceAtMost(10))
        a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 170f))
    }
    graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 48.dp.toPx()
        val s = 0.96f + 0.04f * v
        scaleX = s; scaleY = s
    }
}

val CardShape = RoundedCornerShape(28.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    strong: Boolean = false,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().pressable(onClick).glass(CardShape, strong).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Card title row in Samsung Health style: bold title, optional coloured icon and a chevron. */
@Composable
fun CardHeader(title: String, icon: ImageVector? = null, color: Color = Accent.Swim, trailing: String? = null) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            IconBadge(icon, color, size = 30.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = p.textDim)
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}

/** Samsung Health–style metric tile: icon badge, big number, small unit and label. */
@Composable
fun MetricTile(
    icon: ImageVector,
    color: Color,
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(
        modifier.glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = p.textDim, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = p.text, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = p.textDim, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun MetricGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons, chips, fields
// ---------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Accent.Swim) {
    Box(
        modifier
            .pressable(if (enabled) onClick else null)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(color, Accent.SwimDeep)), alpha = if (enabled) 1f else 0.4f)
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color? = null) {
    val p = LocalPalette.current
    Row(
        modifier.pressable(onClick).glass(RoundedCornerShape(50), strong = true).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint ?: p.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = tint ?: p.text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 44.dp, tint: Color? = null) {
    val p = LocalPalette.current
    Box(Modifier.size(size).pressable(onClick).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint ?: p.text, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, color: Color = Accent.Swim) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (selected) color else Color.Transparent, label = "chip")
    val fg by animateColorAsState(if (selected) Color.White else p.text, label = "chipText")
    Box(
        Modifier.pressable(onClick).glass(RoundedCornerShape(50)).background(bg, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = p.glassStrong,
            unfocusedContainerColor = p.glassTint,
            disabledContainerColor = p.glassTint,
            focusedBorderColor = Accent.Swim,
            unfocusedBorderColor = p.borderHi.copy(alpha = 0.5f),
            focusedLabelColor = Accent.Swim,
            unfocusedLabelColor = p.textDim,
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            cursorColor = Accent.Swim,
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// Headers & bars
// ---------------------------------------------------------------------------------------------

/** iOS-style large title used at the top of each tab. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 4.dp).appear(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), style = MaterialTheme.typography.labelLarge, color = p.textDim, letterSpacing = 1.sp)
            Text(title, style = MaterialTheme.typography.displaySmall, color = p.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

/** Header for pushed screens: glass back button + title. */
@Composable
fun DetailHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", onBack, size = 42.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.weight(1f), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

data class TabItem(val route: String, val label: String, val icon: ImageVector)

/** Floating glass tab bar with a liquid, spring-animated selection pill. */
@Composable
fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    BoxWithConstraints(modifier.fillMaxWidth().height(70.dp).glass(RoundedCornerShape(35.dp), strong = true).padding(6.dp)) {
        val itemW = maxWidth / items.size
        val x by animateDpAsState(itemW * selected.coerceAtLeast(0), spring(dampingRatio = 0.68f, stiffness = 380f), label = "pill")
        if (selected >= 0) Box(
            Modifier.offset(x = x).width(itemW).height(maxHeight)
                .clip(RoundedCornerShape(29.dp))
                .background(Brush.verticalGradient(listOf(Accent.Swim.copy(alpha = 0.32f), Accent.SwimDeep.copy(alpha = 0.22f))))
                .border(1.dp, Color.White.copy(alpha = if (p.dark) 0.25f else 0.7f), RoundedCornerShape(29.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                val tint by animateColorAsState(if (active) (if (p.dark) Color.White else Color(0xFF0A5BD8)) else p.textDim, label = "tab")
                val scale by animateFloatAsState(if (active) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 400f), label = "tabScale")
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                    Text(item.label, style = TextStyle(fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium), color = tint)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------------------------

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.textDim)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = color ?: LocalPalette.current.text)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 6.dp, top = 8.dp),
        style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, color = LocalPalette.current.textDim,
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = LocalPalette.current.textDim)
    }
}

/** Divider-free row: label left, value right. */
@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.textDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = valueColor ?: p.text)
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun fmtDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)
fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun fmtTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

/**
 * The Hamuk Swims app icon (an H standing in the water), drawn as vectors so it's crisp at any size.
 * Coordinates are in the 108-unit icon design space, where the visible icon spans 6..102.
 */
@Composable
fun BrandIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 96f
        fun x(v: Float) = (v - 6f) * s
        fun y(v: Float) = (v - 6f) * s
        val squircle = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(54f), y(6f))
            cubicTo(x(88f), y(6f), x(102f), y(20f), x(102f), y(54f))
            cubicTo(x(102f), y(88f), x(88f), y(102f), x(54f), y(102f))
            cubicTo(x(20f), y(102f), x(6f), y(88f), x(6f), y(54f))
            cubicTo(x(6f), y(20f), x(20f), y(6f), x(54f), y(6f))
            close()
        }
        drawPath(squircle, Brush.linearGradient(listOf(Color(0xFF1366F2), Color(0xFF0A2E7A)), Offset.Zero, Offset(this.size.width, this.size.height)))
        val wave = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(18f), y(57f))
            cubicTo(x(25f), y(49f), x(31f), y(49f), x(38f), y(57f))
            cubicTo(x(45f), y(65f), x(51f), y(65f), x(58f), y(57f))
            cubicTo(x(65f), y(49f), x(71f), y(49f), x(78f), y(57f))
            cubicTo(x(83f), y(62f), x(87f), y(62f), x(90f), y(60f))
        }
        drawPath(wave, Color(0xFF5CE1FF), style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 8f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        listOf(30f, 68f).forEach { left ->
            drawRoundRect(Color.White, Offset(x(left), y(31f)), androidx.compose.ui.geometry.Size(10f * s, 46f * s),
                androidx.compose.ui.geometry.CornerRadius(5f * s, 5f * s))
        }
    }
}

/** iOS-style segmented control on glass with an animated selection pill. */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp).glass(RoundedCornerShape(50)).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.75f, stiffness = 420f), label = "seg")
        Box(Modifier.offset(x = x).width(w).height(maxHeight).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(Accent.Swim, Accent.SwimDeep))))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val c by animateColorAsState(if (i == selected) Color.White else p.text, label = "segText")
                Box(Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    contentAlignment = Alignment.Center) {
                    Text(o, color = c, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
