package com.munin.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A tap that feels like iOS: no ripple, the row dims a little while pressed and springs back. */
@Composable
fun Modifier.pressable(onClick: (() -> Unit)?, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(0.dp)): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val overlay by animateColorAsState(if (pressed) Munin.colors.label.copy(alpha = 0.08f) else Color.Transparent, label = "press")
    return this.clip(shape).background(overlay).clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
fun Icon(icon: ImageVector, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Image(icon, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

/** The page title: big, bold and left-aligned, with an optional quiet line under it. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Munin.colors.label)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Munin.colors.secondaryLabel)
    }
}

/** Fades and slides its content up the first time it appears, after [delayMs]; used to stagger tiles in. */
@Composable
fun EnterOnce(delayMs: Int = 0, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(delayMs.toLong()); shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(380), label = "enter-a")
    val dy by animateFloatAsState(if (shown) 0f else 14f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "enter-y")
    Box(modifier.graphicsLayer { alpha = a; translationY = dy * density }) { content() }
}

/** A quiet white tile with one accent-coloured icon, a title and a caption: a one-tap shortcut, in the manner of Apple's Shortcuts and Home tiles. */
@Composable
fun ShortcutTile(title: String, caption: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "tile")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.scale(scale).clip(shape).background(Munin.colors.card).clickable(interactionSource = source, indication = null, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Icon(icon, Munin.colors.tint, 26.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Munin.colors.label, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * An inset grouped section: a small uppercase header, a rounded white card whose rows are divided by hairlines, and an optional footnote.
 * The rows themselves are plain composables; use [ListRow] for the usual one and [GroupDivider] between them.
 */
@Composable
fun Section(header: String? = null, footer: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) Text(header.uppercase(), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Munin.colors.card), content = content)
        if (footer != null) Text(footer, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel)
    }
}

@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(Munin.colors.separator))
}

/** One row of a section: optional leading icon or picture, a title and subtitle, optional trailing text or control, and a chevron when it opens something. */
@Composable
fun ListRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, subtitleColor: Color = Munin.colors.secondaryLabel, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null, titleMaxLines: Int = 2, titleColor: Color = Munin.colors.label,
) {
    Row(
        modifier.fillMaxWidth().pressable(onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        if (trailing != null) trailing()
        if (chevron) Icon(IosIcons.Chevron, Munin.colors.tertiaryLabel, 14.dp)
    }
}

/** A small rounded square with an accent-coloured icon on a soft grey, for rows that need a leading symbol. */
@Composable
fun IconTile(icon: ImageVector, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Munin.colors.fill), contentAlignment = Alignment.Center) { Icon(icon, Munin.colors.tint, size * 0.58f) }
}

/** A free-standing white rounded card, flat on the grey page, optionally tappable. */
@Composable
fun IosCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Munin.colors.card).pressable(onClick, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

enum class ButtonStyle { Filled, Tinted, Plain }

/** Capsule-shaped buttons: filled blue for the main action, soft-blue tinted for secondary ones, plain blue text for the rest. */
@Composable
fun IosButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: ButtonStyle = ButtonStyle.Tinted, enabled: Boolean = true, destructive: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "btn")
    val base = if (destructive) Munin.colors.red else Munin.colors.tint
    val (bg, fg) = when (style) {
        ButtonStyle.Filled -> base to Color.White
        ButtonStyle.Tinted -> base.copy(alpha = 0.14f) to base
        ButtonStyle.Plain -> Color.Transparent to base
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier.scale(scale).alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (style == ButtonStyle.Plain) 8.dp else 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1) }
}

/** Material's switch, dressed like iOS: green when on, a wide pill, no outline. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Munin.colors.green, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = Munin.colors.fill.copy(alpha = 0.35f), uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = Munin.colors.green.copy(alpha = 0.4f), disabledUncheckedTrackColor = Munin.colors.fill, disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** The iOS segmented control: a grey track with a white thumb that slides under the chosen option. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Munin.colors.fill).padding(2.dp)) {
        val segment = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(segment * selected.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = 500f), label = "seg-x")
        Box(Modifier.offset(x = x).width(segment).height(32.dp).background(Munin.colors.card, RoundedCornerShape(9.dp)))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).height(32.dp).clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Munin.colors.label else Munin.colors.secondaryLabel, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
            }
        }
    }
}

/** The iOS search field: a grey rounded fill, a magnifier, the text, a clear button once there is something to clear, and room for a mic on the right. */
@Composable
fun SearchField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Munin.colors.fill).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IosIcons.Search, Munin.colors.secondaryLabel, 19.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Munin.colors.label), cursorBrush = SolidColor(Munin.colors.tint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
            )
        }
        if (value.isNotEmpty()) Icon(IosIcons.Clear, Munin.colors.tertiaryLabel, 19.dp, Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onValueChange("") })
        trailing?.invoke()
    }
}

/** Small grey explanatory text, as under a section. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, color: Color = Munin.colors.secondaryLabel) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)

val ScreenPadding = PaddingValues(horizontal = 16.dp)

/** A thin rounded progress bar, grey track and blue fill that eases to its value. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Munin.colors.fill)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(50)).background(Munin.colors.tint))
    }
}

/** The iOS navigation bar's back control: a blue chevron and the name of where it goes back to. */
@Composable
fun BackBar(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 6.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).pressable(onBack).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(IosIcons.ChevronBack, Munin.colors.tint, 22.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.tint)
    }
}
