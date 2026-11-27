package com.calendaralarm.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.ui.theme.NightTokens

/** 下タブの1つ。経路・名前・絵は MainActivity が決める */
internal data class NightTabItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** 板 .tab の値 (CSS 115-119行) */
internal object NightTabBarSpec {
    const val HEIGHT_DP = 72
    const val SIDE_PAD_DP = 6
    const val INDICATOR_WIDTH_DP = 56
    const val INDICATOR_HEIGHT_DP = 30
    const val INDICATOR_CORNER_DP = 15
    const val ICON_DP = 22
    const val ICON_LABEL_GAP_DP = 4
    const val LABEL_SP = 11
    const val INDICATOR_ALPHA = 0.15f
}

internal const val NIGHT_TAB_BAR_TAG = "night-tab-bar"
internal fun nightTabTag(route: String) = "night-tab-$route"
internal fun nightTabIndicatorTag(route: String) = "night-tab-indicator-$route"

/**
 * 下タブ (板 .tab)。画面の下に付く高さ 72 の帯で、上に 1dp の --hair を引く。
 * 帯の面はナビゲーションバーの後ろまで続け、タブはその分だけ上に出す。
 * 名前は下の文字で読まれるので、印の contentDescription は持たない。
 */
@Composable
internal fun NightTabBar(
    items: List<NightTabItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hair = NightTokens.hair
    Column(
        modifier
            .fillMaxWidth()
            // CSS の box-shadow: 0 -1px 0 --hair。帯の外 (上) に 1dp 引く
            .drawBehind {
                drawRect(hair, topLeft = Offset(0f, -1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            }
            .background(scheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(NightTabBarSpec.HEIGHT_DP.dp)
                .padding(horizontal = NightTabBarSpec.SIDE_PAD_DP.dp)
                .selectableGroup()
                .testTag(NIGHT_TAB_BAR_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val sel = currentRoute == item.route
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(item.route) })
                        .testTag(nightTabTag(item.route)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NightTabBarSpec.ICON_LABEL_GAP_DP.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier
                            .size(NightTabBarSpec.INDICATOR_WIDTH_DP.dp, NightTabBarSpec.INDICATOR_HEIGHT_DP.dp)
                            .testTag(nightTabIndicatorTag(item.route))
                            .background(
                                if (sel) scheme.primary.copy(alpha = NightTabBarSpec.INDICATOR_ALPHA) else Color.Transparent,
                                RoundedCornerShape(NightTabBarSpec.INDICATOR_CORNER_DP.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (sel) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(NightTabBarSpec.ICON_DP.dp),
                        )
                    }
                    Text(
                        stringResource(item.labelRes),
                        fontSize = NightTabBarSpec.LABEL_SP.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
