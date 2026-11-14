package dev.mkzk.manifold.hub.app

import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

private val ItemRadius = 27.dp
private const val PILL_STRETCH = 0.16f
private const val PILL_DAMPING = 0.82f
private const val PILL_STIFFNESS = 340f

internal fun <T> pillSpring() = spring<T>(dampingRatio = PILL_DAMPING, stiffness = PILL_STIFFNESS)

private fun PagerState.position() = currentPage + currentPageOffsetFraction

@Composable
internal fun TopBar(titles: List<String>, pager: PagerState, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            titles[pager.currentPage],
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).graphicsLayer { alpha = 1f - 2f * abs(pager.currentPageOffsetFraction) },
        )
        actions()
    }
}

internal class NavItem(val icon: ImageVector, val label: String, val badge: Int)

// The pill and the colours read the pager while drawing, so a swipe never recomposes or lays out this bar.
@Composable
internal fun BottomBar(items: List<NavItem>, pager: PagerState, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val idle = scheme.onSurfaceVariant
    val radius = with(LocalDensity.current) { ItemRadius.toPx() }
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)) {
        Surface(shape = CircleShape, color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant), shadowElevation = 6.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(5.dp)
                    .drawBehind {
                        val at = pager.position()
                        val cell = size.width / items.size
                        val width = cell * (1f + PILL_STRETCH * sin(PI.toFloat() * (at - floor(at))))
                        drawRoundRect(accent.copy(alpha = 0.2f), Offset(at * cell - (width - cell) / 2, 0f), Size(width, size.height), CornerRadius(radius))
                    },
            ) {
                items.forEachIndexed { index, item ->
                    val near = { (1f - abs(pager.position() - index)).coerceIn(0f, 1f) }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .selectable(selected = pager.currentPage == index, interactionSource = null, indication = null, role = Role.Tab) { onSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val painter = rememberVectorPainter(item.icon)
                        val icon = @Composable {
                            Box(
                                Modifier.size(22.dp).drawBehind {
                                    with(painter) { draw(size, colorFilter = ColorFilter.tint(lerp(idle, accent, near()))) }
                                },
                            )
                        }
                        if (item.badge > 0) BadgedBox(badge = { Badge { Text(item.badge.toString()) } }) { icon() } else icon()
                        BasicText(item.label, style = MaterialTheme.typography.labelSmall, color = { lerp(idle, accent, near()) }, maxLines = 1)
                    }
                }
            }
        }
    }
}
