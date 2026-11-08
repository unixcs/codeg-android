package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * InsightKit — 批v169 立的「Tremor/shadcn 仪表盘」可复用组件库，供 Token
 * 用量页与后续全局 UI 重构（批v170）共用。设计口径（主人拍板）：
 * - 90% 黑白灰 + 10% 主题色点缀：组件一律走 [CodegTheme.colors] 的中性角色
 *   （textPrimary/Secondary/Tertiary/hairline/surfaceStroke），不做彩色大色块。
 * - 数字强制 [CodegMono] 等宽加粗；辅助说明小一号且低透明。
 * - 选中态 = 极低透明浅灰底 + 细边框；进度 = 1dp 细线；图表无网格线。
 * 组件只管画，不管数据语义（token/金额/次数皆可）；所有色值随主题（深浅色
 * 自动适配），批v170 若调色板这里零改动。
 */

private val InsightCapsuleHeight = 32.dp
private val InsightChipCorner = 16.dp

/**
 * shadcn 风格胶囊筛选器（替代 CodegSegmented 的蓝色选中块）。选中 = 浅灰
 * 低透明底 + 细边框；未选中 = 透明 + hairline 边框；溢出横向滚动。
 */
@Composable
fun CapsuleChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val selectedShape = remember { RoundedCornerShape(InsightChipCorner) }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(InsightCapsuleHeight)
                    .clip(selectedShape)
                    .background(if (selected) colors.textPrimary.copy(alpha = 0.10f) else Color.Transparent)
                    .border(
                        width = 0.75.dp,
                        color = if (selected) colors.surfaceStroke else colors.hairline,
                        shape = selectedShape,
                    )
                    .clickable(role = Role.Tab, onClickLabel = label) { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 「名次 + 名称 + 数值 + 百分比 + 1dp 细条」的一行数据（RankedBarList 单元）。 */
data class RankedRow(
    val name: String,
    val valueText: String,
    /** 0f..1f，相对榜 first 的占比（调用方算好，组件不假设语义）。 */
    val fraction: Float,
    /** 形如 "58.7%"；null 则不显示百分比列。 */
    val percentText: String? = null,
)

/** Tremor 风格用量分布榜：行距压缩、粗进度条退役，底部 1dp 细线示意占比。 */
@Composable
fun RankedBarList(
    rows: List<RankedRow>,
    modifier: Modifier = Modifier,
    /** 榜首从几号开始数（默认 1 → "01"）。 */
    rankStart: Int = 1,
) {
    if (rows.isEmpty()) return
    val colors = CodegTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = (rankStart + index).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = CodegMono,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = CodegMono,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    if (row.percentText != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.percentText,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = CodegMono,
                            color = colors.textTertiary,
                        )
                    }
                }
                HairlineBar(fraction = row.fraction)
            }
        }
    }
}

/** 1dp 极细进度条：hairline 灰轨 + 浅色填充（shadcn「细线状态」口径）。 */
@Composable
fun HairlineBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    fillColor: Color? = null,
) {
    val colors = CodegTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.hairline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(1.dp)
                .background(fillColor ?: colors.textPrimary.copy(alpha = 0.72f)),
        )
    }
}

/** 2×2 指标格单元：小标签 + 等宽加粗数值 + 更淡的副行。 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val colors = CodegTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = CodegMono,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 细描边环形进度圈（web 参考样图的缓存命中率圈）。track = hairline，
 * 进度 = 浅色圆帽弧；中心文案调用方叠（本组件只画圈）。
 */
@Composable
fun RingProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    ringColor: Color? = null,
    strokeWidth: Dp = 3.dp,
) {
    val colors = CodegTheme.colors
    val stroke = strokeWidth
    Canvas(modifier = modifier) {
        val inset = stroke.toPx() / 2f
        val arcSize = Size(size.width - stroke.toPx(), size.height - stroke.toPx())
        val arcRect = Rect(Offset(inset, inset), arcSize)
        drawArc(
            color = colors.hairline,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = arcRect.topLeft,
            size = arcSize,
            style = Stroke(width = stroke.toPx()),
        )
        val sweep = 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            drawArc(
                color = ringColor ?: colors.textPrimary,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcSize,
                style = Stroke(width = stroke.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/** 迷你柱状图的单根柱：[cached]（底段，浅灰）+ [fresh]（顶段，亮色）。 */
data class BarPoint(val cached: Float, val fresh: Float)

/**
 * Tremor 风格极简柱状图：圆角顶、无网格线、仅一条浅灰基线。两段堆叠语义
 * 由调用方定义（Token 页 = 缓存读取 + 新算）。零值日不画；非零但极小给
 * 2px 保底可见。y 轴不做刻度——顶部极值由调用方用文字标注（Tremor 口径）。
 */
@Composable
fun MiniBarChart(
    points: List<BarPoint>,
    modifier: Modifier = Modifier,
    /** 顶段（强调）颜色，默认近白；底段固定用 textTertiary 低透明。 */
    freshColor: Color? = null,
    cachedColor: Color? = null,
    barCorner: Dp = 2.dp,
    minBarGap: Dp = 1.dp,
) {
    val colors = CodegTheme.colors
    val maxTotal = points.maxOfOrNull { it.cached + it.fresh } ?: 0f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || maxTotal <= 0f) return@Canvas
        // 基线：浅灰 1px 横线（唯一坐标轴）
        drawRect(
            color = colors.hairline,
            topLeft = Offset(0f, h - 1f),
            size = Size(w, 1f),
        )
        val n = points.size
        if (n == 0) return@Canvas
        val gapPx = minBarGap.toPx()
        val slot = w / n
        val barW = (slot - gapPx).coerceAtLeast(1f)
        val fresh = freshColor ?: colors.textPrimary
        val cached = cachedColor ?: colors.textTertiary.copy(alpha = 0.55f)
        val corner = barCorner.toPx().coerceAtMost(barW / 2f)
        val plotH = h - 1f
        points.forEachIndexed { i, p ->
            val total = p.cached + p.fresh
            if (total <= 0f) return@forEachIndexed
            val barH = ((total / maxTotal) * plotH).coerceAtLeast(2f)
            val x = i * slot + (slot - barW) / 2f
            val top = h - 1f - barH
            val barRect = Rect(Offset(x, top), Size(barW, barH))
            // 圆角只留在柱顶：clip 一条 top-only 圆角路径，段内画平矩形
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = barRect,
                        topLeft = CornerRadius(corner, corner),
                        topRight = CornerRadius(corner, corner),
                    ),
                )
            }
            clipPath(path) {
                // 顶段 fresh
                val freshH = (p.fresh / total) * barH
                if (freshH > 0f) {
                    drawRect(
                        color = fresh,
                        topLeft = Offset(x, top),
                        size = Size(barW, freshH),
                    )
                }
                // 底段 cached 占其余
                val cachedH = barH - freshH
                if (cachedH > 0f) {
                    drawRect(
                        color = cached,
                        topLeft = Offset(x, top + freshH),
                        size = Size(barW, cachedH),
                    )
                }
            }
        }
    }
}

/**
 * GitHub 风 7×24 作息热力图（行=周一…周日，列=0…23 时）。values 按
 * `weekday*24+hour` 索引，0f..1f；0 = 近底色空格，1 = 最亮。单 Canvas 绘制
 * （168 格不产生组合体），格间 2dp 缝，格圆角 2dp。
 */
@Composable
fun HeatmapMatrix(
    values: FloatArray,
    modifier: Modifier = Modifier,
    cellCorner: Dp = 2.dp,
    cellGap: Dp = 2.dp,
    hotColor: Color? = null,
) {
    val colors = CodegTheme.colors
    val hot = hotColor ?: colors.textPrimary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val cols = 24
        val rows = 7
        val gap = cellGap.toPx()
        val cellW = (w - gap * (cols - 1)) / cols
        val cellH = (h - gap * (rows - 1)) / rows
        if (cellW <= 0f || cellH <= 0f) return@Canvas
        val corner = cellCorner.toPx().coerceAtMost(minOf(cellW, cellH) / 2f)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                val v = values.getOrNull(idx)?.coerceIn(0f, 1f) ?: 0f
                val color = when {
                    v <= 0f -> colors.hairline
                    // 4 档灰阶：越热越亮（GitHub 口径），90/10 规范下不用彩色
                    else -> hot.copy(alpha = 0.18f + 0.82f * v * v)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * (cellW + gap), row * (cellH + gap)),
                    size = Size(cellW, cellH),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }
    }
}

/** 周期涨跌徽标：↗ 绿 ↘ 红；previous 缺失（null）或两周期持平（0）不渲染。
 *  极端环比（prev=1 现值巨大）封顶 ±999%——徽标是方向提示，不是精确读数。 */
@Composable
fun DeltaBadge(delta: Float?, modifier: Modifier = Modifier) {
    if (delta == null || delta == 0f) return
    val colors = CodegTheme.colors
    val up = delta > 0f
    val color = if (up) colors.success else colors.danger
    val arrow = if (up) "↗" else "↘"
    val percent = when {
        kotlin.math.abs(delta) > 9.99f ->
            String.format(java.util.Locale.ROOT, "%+.0f%%", kotlin.math.sign(delta) * 999f)
        kotlin.math.abs(delta) >= 0.1f ->
            String.format(java.util.Locale.ROOT, "%+.0f%%", delta * 100)
        else ->
            String.format(java.util.Locale.ROOT, "%+.1f%%", delta * 100)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(InsightChipCorner))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = arrow,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Text(
            text = percent,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = CodegMono,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** 区块标题（卡片内小节）：小号、低透明、可选尾部说明。 */
@Composable
fun InsightSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = CodegTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = CodegMono,
                color = colors.textTertiary,
            )
        }
    }
}
