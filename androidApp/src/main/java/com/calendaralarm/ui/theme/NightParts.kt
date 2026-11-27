package com.calendaralarm.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.R

// 板 (design/asset の CSS) の共通部品。画面ごとに同じ形を使うため、値はここに集める。
// このファイルに日本語の文字列は書かない (scripts/hardcoded-ja/check.mjs の数が増えるため)。

/** 色の明暗。端末の設定ではなく、今の MaterialTheme の背景で決める (鳴動画面は端末がライトでもダークで描く) */
@Composable
fun isNightDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** CSS 変数のうち、MaterialTheme の色の名前と1対1に当たらない物 */
object NightTokens {
    /** --raised */
    val raised: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** --hair */
    val hair: Color
        @Composable get() = if (isNightDark()) Color(0x0FFFFFFF) else Color(0x1214171F)

    /** --out */
    val out: Color
        @Composable get() = if (isNightDark()) Color(0xFF7B8494) else Color(0xFF737B8A)

    /** --segtr */
    val segTrack: Color
        @Composable get() = if (isNightDark()) Color(0xFF141821) else Color(0xFFE6E8EE)

    /** --segon */
    val segOn: Color
        @Composable get() = if (isNightDark()) Color(0xFF262C38) else Color(0xFFFFFFFF)

    /** --scrim */
    val scrim: Color
        @Composable get() = if (isNightDark()) Color(0xA3030407) else Color(0x7514171F)

    /** --acl */
    val accentRing: Color
        @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = if (isNightDark()) 0.30f else 0.26f)

    /** --ngl */
    val warnRing: Color
        @Composable get() = if (isNightDark()) Color(0x52FF8A80) else Color(0x47B3261E)

    /** --okl */
    val okRing: Color
        @Composable get() = if (isNightDark()) Color(0x526FD6A6) else Color(0x4718734B)
}

object NightShapes {
    /** .card */
    val card = RoundedCornerShape(20.dp)

    /** .hero */
    val hero = RoundedCornerShape(22.dp)

    /** .banner・.prev */
    val banner = RoundedCornerShape(16.dp)

    /** .c・.btn・.ob・.ghost */
    val pill = RoundedCornerShape(50)
}

/** .card: 角 20・背景 --card・内側に 1dp の --hair・余白 上下 14 左右 16・中の間 10 */
@Composable
fun NightCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier
            .fillMaxWidth()
            .clip(NightShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, NightTokens.hair, NightShapes.card)
            .then(clickModifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** .card.ls: 一覧用。余白は上下 4 左右 16・中の間 0 (行の余白は行が持つ) */
@Composable
fun NightListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    NightCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        spacing = 0.dp,
        content = content,
    )
}

const val NIGHT_DIVIDER_TAG = "night-divider"

/** 行の間の 1dp の線 (--div) */
@Composable
fun NightDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .testTag(NIGHT_DIVIDER_TAG),
    )
}

/** .btn: 高さ 52 (既定)・角は高さの半分・背景 --ac・文字 --onac・SemiBold */
@Composable
fun NightButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    fontSize: TextUnit = 16.sp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** .lnk: 幅いっぱい・高さ 44 以上・14sp Medium・--mu・背景なし */
@Composable
fun NightLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** .ico: 60 の丸に 1dp の ringColor、外に 80 の丸 (濃さ .5)。印 28。外の余白 10.5 (外の丸の線が外へ 0.5 出るので、親に切られないよう全体 81) */
@Composable
fun NightIconRings(
    icon: ImageVector,
    ringColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(10.5.dp)
            .size(60.dp)
            .drawBehind {
                drawCircle(
                    color = ringColor.copy(alpha = ringColor.alpha * 0.5f),
                    radius = 40.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .border(1.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
    }
}

/** .sheet::before: 36×4・角 2・色 outline (--bd)・上 12・下 16 (margin 6 と gap 10) */
@Composable
fun NightSheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .padding(top = 12.dp, bottom = 16.dp)
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
    }
}

private val NightSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * .dim と .sheet: 下から出る面。上の角 28・面 --card・上に 1dp の --hair・つまみは [NightSheetHandle]。
 * 上の線は box-shadow: 0 -1px 0 --hair。左右と下の線は画面の端に重なり、白 6% なので見えない
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightModalSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, NightTokens.hair, NightSheetShape),
        shape = NightSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        scrimColor = NightTokens.scrim,
        dragHandle = { NightSheetHandle() },
        content = content,
    )
}

const val NIGHT_TOP_BAR_BACK_TAG = "night-top-bar-back"

/**
 * .top と .back と .h1: 高さ 78 以上・余白 上 26 左右 12 下 8・戻る 44×44 (印 22・--tx)・間 2・
 * 題は titleSize・SemiBold・行の高さ 1.35。右に actions (44×44・印 22) を置く。
 * IconButton は押した時の丸が 40dp で板の 44 と合わないので使わない
 */
@Composable
fun NightTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 21.sp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backLabel = stringResource(R.string.common_back)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .testTag(NIGHT_TOP_BAR_BACK_TAG)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = titleSize,
            fontWeight = FontWeight.SemiBold,
            lineHeight = titleSize * 1.35f,
        )
        actions()
    }
}
