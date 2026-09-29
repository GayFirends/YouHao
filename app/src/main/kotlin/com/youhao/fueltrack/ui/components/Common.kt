package com.youhao.fueltrack.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youhao.fueltrack.ui.theme.EyebrowStyle
import com.youhao.fueltrack.ui.theme.MetricValueSmallStyle
import com.youhao.fueltrack.ui.theme.MetricValueStyle
import com.youhao.fueltrack.ui.theme.NightAccent
import com.youhao.fueltrack.ui.theme.PineAccentSoft
import com.youhao.fueltrack.ui.theme.SapInk
import com.youhao.fueltrack.ui.theme.Signal
import com.youhao.fueltrack.ui.theme.accentToHeroBrush
import com.youhao.fueltrack.ui.theme.heroColor
import com.youhao.fueltrack.ui.theme.heroMutedColor
import com.youhao.fueltrack.ui.theme.metricBrush
import com.youhao.fueltrack.ui.theme.numeric
import com.youhao.fueltrack.ui.theme.softShadowColor

// ---------------------------------------------------------------------------
// 基础修饰符
// ---------------------------------------------------------------------------

/**
 * 松间仪表盘风格下所有卡片的统一外观：18dp 圆角 + 品牌色相的柔和投影。
 *
 * 投影没有走 Card 自带的 elevation（那是黑色投影），而是用 [Modifier.shadow] 单独画一层带色相的，
 * 所以调用点必须把 Card 的 elevation 设成 0，否则会出现两层影子。
 * API 26 / 27 会忽略彩色投影、退化成普通阴影，不影响布局与可读性。
 *
 * 需要外边距时把布局修饰符**传进来**（`softCardModifier(Modifier.padding(top = 12.dp))`），
 * 而不是接在后面，否则投影会画在整块区域上、卡片看起来大了一圈。
 */
@Composable
fun softCardModifier(modifier: Modifier = Modifier): Modifier = modifier.shadow(
    elevation = 9.dp,
    shape = MaterialTheme.shapes.medium,
    clip = true,
    ambientColor = softShadowColor(),
    spotColor = softShadowColor(),
)

/**
 * 按下时轻微缩小 —— 对应 CSS `.button:active { transform: translateY(1px) }` 的触感。
 *
 * 用弹簧而不是 `tween`：手指按下的瞬间要有反馈，松开后回弹稍带一点回稳感。
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scaleTo: Float = 0.972f,
    onClickLabel: String? = null,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "press-scale",
    )
    val indication = LocalIndication.current
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * 「入场」动画：淡入 + 轻微上移，对应 CSS `@keyframes page-arrive`
 * （`opacity 0 → 1`，`translateY(4px) → 0`，`.22s ease`）。
 *
 * [index] 让同一屏里的卡片错峰出现，最多累加 7 档，再长的列表也不会越等越久。
 */
@Composable
fun StaggeredAppear(
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    val progress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(
            durationMillis = 240,
            delayMillis = index.coerceIn(0, 7) * 32,
            easing = FastOutSlowInEasing,
        ),
        label = "staggered-appear",
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 14f
        },
    ) { content() }
}

/** `.metric.featured` 的 24px 网格线：`rgba(216,255,114,.055)`。 */
private fun Modifier.dashboardGrid(cell: Dp, lineColor: Color): Modifier = drawBehind {
    val step = cell.toPx().coerceAtLeast(1f)
    var x = step
    while (x < size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** `.metric.featured` 的青柠光晕 + 右侧装饰圆环。 */
private fun Modifier.orbGlow(): Modifier = drawBehind {
    val center = Offset(size.width * 0.88f, size.height * 0.1f)
    val radius = size.minDimension * 0.95f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Signal.copy(alpha = 0.18f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = Signal.copy(alpha = 0.2f),
        radius = 66.dp.toPx(),
        center = Offset(size.width - 30.dp.toPx(), size.height * 0.52f),
        style = Stroke(width = 1.dp.toPx()),
    )
}

// ---------------------------------------------------------------------------
// 输入控件
// ---------------------------------------------------------------------------

/**
 * 开关的配色：滑块固定用卡片面色，轨道跟随强调色。
 *
 * M3 默认的选中滑块取 `onPrimary`，在本主题里是深绿，压在绿色轨道上不好看；
 * 换成面色之后两种明暗下都是「白/深灰滑块 + 绿轨道」，对比清楚。
 */
@Composable
fun softSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.surface,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

/**
 * 输入框配色：默认描边只用装饰线的加深版，聚焦时换成松绿 + 更粗的一档。
 *
 * 原设计的聚焦光晕是 `box-shadow: 0 0 0 4px rgba(35,107,80,.11)`，OutlinedTextField 没有外发光槽位，
 * 所以用「描边变松绿」来承担同一份反馈。
 */
@Composable
fun youHaoFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    errorContainerColor = MaterialTheme.colorScheme.surface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
)

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/**
 * 主按钮：青柠底 + 深绿字，对应 CSS `.button.primary`
 * （`background: var(--signal)` / `color: #163329`）。
 *
 * 青柠在深色主题下同样是最高亮的填充，所以两种明暗共用一个填充色，品牌感不会跑掉。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 44.dp else 52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Signal,
            contentColor = SapInk,
            disabledContainerColor = Signal.copy(alpha = 0.34f),
            disabledContentColor = SapInk.copy(alpha = 0.55f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 次按钮：卡片面色 + 描边，对应 CSS `.button.secondary`。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 44.dp else 52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// 卡片
// ---------------------------------------------------------------------------

/**
 * 仪表盘数字卡 —— CSS `.metric` 的两种形态都收在这里。
 *
 * [featured] 打开时是 `.metric.featured`：深绿底 + 24px 青柠网格 + 右侧光晕，
 * 数值换成青柠色，右上角挂「LIVE」小字；关闭时是 `linear-gradient(145deg,#ffffff,#f8faf5)` 的纸白卡。
 */
@Composable
fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    featured: Boolean = false,
    liveLabel: String? = null,
    valueColor: Color? = null,
    small: Boolean = false,
) {
    val hero = heroColor()
    val heroMuted = heroMutedColor()
    Card(
        modifier = softCardModifier(modifier),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (featured) hero else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (featured) {
                        Modifier
                            .background(hero)
                            .dashboardGrid(24.dp, Signal.copy(alpha = 0.055f))
                            .orbGlow()
                    } else {
                        Modifier.background(metricBrush())
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (featured) heroMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (featured && liveLabel != null) {
                        Text(
                            text = liveLabel,
                            style = EyebrowStyle,
                            fontSize = 8.sp,
                            color = Signal,
                        )
                    }
                }
                Text(
                    text = value,
                    style = (if (small) MetricValueSmallStyle else MetricValueStyle).numeric(),
                    color = valueColor
                        ?: if (featured) Signal else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (featured) heroMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 旧名保留：[StatCard] 就是非 featured 的 [MetricCard]，小号数字。 */
@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    MetricCard(
        title = title,
        value = value,
        modifier = modifier,
        caption = caption,
        small = true,
    )
}

/**
 * 深绿渐变卡 —— CSS `.records-summary` / `.sync-summary`：
 * `linear-gradient(135deg, var(--accent), var(--hero))`，白字、次级文字压暗一档。
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = softCardModifier(modifier),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, heroColor()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentToHeroBrush())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            content = content,
        )
    }
}

/** 深绿卡里的一个数字格。 */
@Composable
fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    val heroMuted = heroMutedColor()
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = heroMuted,
        )
        Text(
            text = value,
            style = (if (emphasize) MetricValueStyle else MetricValueSmallStyle).numeric(),
            color = if (emphasize) Signal else Color.White,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 分组面板：`.panel` —— 卡片面 + 细描边，把零散字段收成一块。 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = softCardModifier(modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            content = content,
        )
    }
}

/** 小徽章：日期 / 状态，对应 CSS `.date-badge` 的 `10px 10px 10px 3px` 缺口圆角。 */
@Composable
fun SoftBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 3.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// ---------------------------------------------------------------------------
// 文本
// ---------------------------------------------------------------------------

/**
 * 页眉：小号大写强调字 + 主标题，对应 CSS `.eyebrow` + `.page-title h1`。
 * 每个页面顶端都用它，页面之间才有同一个「排版起点」。
 */
@Composable
fun PageHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = eyebrow.uppercase(),
            style = EyebrowStyle,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .padding(vertical = 32.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A tinted, full-width message — used for the last sync error and for validation warnings. */
@Composable
fun MessageCard(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = softCardModifier(modifier).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 描边跟着内容色走：同一个 MessageCard 还要承载错误（红）与警告语义，
        // 写死绿色会在错误提示上串色。
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
    }
}

/**
 * 开关行：左边一句说明、右边一个开关，中间留出空隙。
 * 设置页里这种行出现了三次，收成一个组件避免三处各写一遍对齐参数。
 */
@Composable
fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = softSwitchColors())
    }
}

/**
 * 底部的环境光晕层 —— body 的
 * `radial-gradient(circle at 90% 0, rgba(164,207,170,.24), transparent 19rem)`。
 * 套在整块内容区外面，让四个标签页共享同一层底色，滑动时背景不会跟着变。
 */
@Composable
fun Modifier.ambientGlow(): Modifier {
    val glow = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color(0xFFA4CFAA).copy(alpha = 0.30f)
    }
    return this.drawBehind {
        val radius = size.width * 0.95f
        val center = Offset(size.width * 0.92f, 0f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/** 强调色的小色块（导航选中态、装饰点）。 */
@Composable
fun accentChipColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        PineAccentSoft
    }

/** 深色主题下青柠依旧可用，但强调文字要换成提亮后的绿。 */
@Composable
fun brandStrongColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) NightAccent else MaterialTheme.colorScheme.primary

/** 一个固定尺寸的方块，用来量间距 / 占位。 */
@Composable
fun Spacer(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size))
}
