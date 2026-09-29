package com.youhao.fueltrack.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.youhao.fueltrack.R
import com.youhao.fueltrack.ui.components.accentChipColor
import com.youhao.fueltrack.ui.components.ambientGlow
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.conflicts.ConflictsScreen
import com.youhao.fueltrack.ui.overview.OverviewScreen
import com.youhao.fueltrack.ui.records.RecordEditorScreen
import com.youhao.fueltrack.ui.records.RecordsScreen
import com.youhao.fueltrack.ui.settings.SettingsScreen
import com.youhao.fueltrack.ui.theme.SapInk
import com.youhao.fueltrack.ui.theme.Signal
import com.youhao.fueltrack.ui.theme.softShadowColor
import com.youhao.fueltrack.ui.vehicles.VehiclesScreen
import kotlinx.coroutines.launch

/**
 * The four top-level sections, in tab order. The navigation bar carries hand-drawn vector icons
 * (`res/drawable/ic_nav_*.xml`) because the project has no icon-font dependency; see `Formatters.kt`.
 */
enum class MainSection(val route: String, val label: String, val title: String) {
    OVERVIEW("overview", "概览", "油迹"),
    RECORDS("records", "记录", "加油记录"),
    VEHICLES("vehicles", "车辆", "车辆"),
    SETTINGS("settings", "设置", "设置"),
}

object Routes {
    /**
     * The pager host. The four [MainSection] routes are no longer navigation destinations — they are
     * pages inside [HorizontalPager] — so all four live behind this single route and detail screens
     * (`records/new`, `records/edit/{id}`, `conflicts`) push on top of it.
     */
    const val MAIN = "main"
    const val RECORD_NEW = "records/new"

    /**
     * Kept distinct from [RECORD_NEW] on purpose: `records/new` and `records/{recordId}` would both
     * match a `records/new` navigation, so the edit route carries its own literal segment.
     */
    const val RECORD_EDIT = "records/edit/{recordId}"
    const val CONFLICTS = "conflicts"

    fun recordEdit(recordId: String): String = "records/edit/$recordId"
}

/** `.page { animation: page-arrive .22s ease }` 的 Compose 版：淡入 + 一点点上移。 */
private val PageEnterSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)

/** 详情页进出：轻微位移 + 淡入淡出，比默认的全屏淡入更清楚「进来了」。 */
private val DetailEnterSpec = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)
private val DetailExitSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
private val DetailEnterSlide = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
private val DetailExitSlide = tween<IntOffset>(durationMillis = 180, easing = FastOutSlowInEasing)

/**
 * Navigation shell for the whole app: a single [Scaffold] with a translucent top bar, a custom
 * bottom bar (4 tabs + a lime centre 「记一笔」 button, mirroring the Vue `nav.bottom-nav`), and a
 * [HorizontalPager] that hosts the four sections so they can be swiped left/right as well as tapped.
 *
 * 底栏左右滑动是这一版最直接的交互收益：切页不再需要瞄准 60dp 宽的图标。
 */
@Composable
fun YouHaoApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onMain = route == null || route == Routes.MAIN

    val pagerState = rememberPagerState(pageCount = { MainSection.entries.size })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentSection = MainSection.entries[pagerState.currentPage.coerceIn(0, MainSection.entries.size - 1)]

    // 侧滑切到别的标签后按返回，先回到第一个标签，而不是直接退出应用。
    BackHandler(enabled = onMain && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0, animationSpec = PageEnterSpec) }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlow(),
        containerColor = Color.Transparent,
        topBar = {
            YouHaoTopBar(
                onMain = onMain,
                section = if (onMain) currentSection else null,
                title = titleFor(route),
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.RECORD_NEW) },
            )
        },
        bottomBar = {
            if (onMain) {
                YouHaoBottomBar(
                    current = currentSection,
                    onSelect = { section ->
                        val target = MainSection.entries.indexOf(section)
                        if (target != pagerState.currentPage) {
                            // 触感先于动画：手指离开屏幕时就确认「点到了」，滑动只是一段余韵。
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                pagerState.animateScrollToPage(target, animationSpec = PageEnterSpec)
                            }
                        }
                    },
                    onAdd = { navController.navigate(Routes.RECORD_NEW) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MAIN,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(DetailEnterSpec) + slideInVertically(DetailEnterSlide) { it / 26 } },
            exitTransition = { fadeOut(DetailExitSpec) },
            popEnterTransition = { fadeIn(DetailEnterSpec) },
            popExitTransition = { fadeOut(DetailExitSpec) + slideOutVertically(DetailExitSlide) { it / 26 } },
        ) {
            composable(Routes.MAIN) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 预组合左右各一页：点标签 / 滑一页时不会先看到空白再填充。
                    beyondViewportPageCount = 1,
                    key = { MainSection.entries[it].route },
                ) { page ->
                    when (MainSection.entries[page]) {
                        MainSection.OVERVIEW -> OverviewScreen(
                            onOpenRecord = { navController.navigate(Routes.recordEdit(it)) },
                            onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                        )

                        MainSection.RECORDS -> RecordsScreen(
                            onAddRecord = { navController.navigate(Routes.RECORD_NEW) },
                            onOpenRecord = { navController.navigate(Routes.recordEdit(it)) },
                        )

                        MainSection.VEHICLES -> VehiclesScreen()

                        MainSection.SETTINGS -> SettingsScreen(
                            onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                        )
                    }
                }
            }
            composable(Routes.RECORD_NEW) {
                RecordEditorScreen(recordId = null, onDone = { navController.popBackStack() })
            }
            composable(Routes.RECORD_EDIT) { entry ->
                RecordEditorScreen(
                    recordId = entry.arguments?.getString("recordId"),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.CONFLICTS) {
                ConflictsScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}

/**
 * 顶栏：72dp 高、半透明纸感底，对应 CSS `.topbar`。
 *
 * 左侧是品牌标记（青柠圆角方块 + 油滴）与当前分区名；右侧在总览页给一个青柠「+」直达记一笔，
 * 详情页则换成强调色的返回圆钮。
 */
@Composable
private fun YouHaoTopBar(
    onMain: Boolean,
    section: MainSection?,
    title: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMain) {
                BrandMark()
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "油迹",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 分区名随滑页淡入淡出，避免「滑到一半标题先跳」的割裂感。
                    AnimatedContent(
                        targetState = section?.title ?: "",
                        transitionSpec = {
                            (fadeIn(PageEnterSpec) + slideInVertically(DetailEnterSlide) { it / 3 })
                                .togetherWith(fadeOut(tween(durationMillis = 140)))
                        },
                        label = "topbar-section",
                    ) { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AddButton(onClick = onAdd)
            } else {
                BackButton(onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** `.brand-mark`：42×42 青柠底，右下角小圆角（`13px 13px 13px 4px`）。 */
@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp, bottomEnd = 13.dp, bottomStart = 4.dp))
            .background(Signal),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_brand_fuel),
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = SapInk,
        )
    }
}

/** `.button.primary.top-add`：青柠实心，配 `0 7px 18px rgba(95,128,46,.13)` 的柔光。 */
@Composable
private fun AddButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .shadow(
                elevation = 8.dp,
                shape = MaterialTheme.shapes.small,
                clip = false,
                ambientColor = softShadowColor(),
                spotColor = softShadowColor(),
            )
            .clip(MaterialTheme.shapes.small)
            .background(Signal)
            .pressable(onClick = onClick, scaleTo = 0.94f, onClickLabel = "记一笔"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_add),
            contentDescription = "记一笔",
            modifier = Modifier.size(22.dp),
            tint = SapInk,
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(MaterialTheme.shapes.small)
            .background(accentChipColor())
            .pressable(onClick = onClick, scaleTo = 0.92f, onClickLabel = "返回"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_back),
            contentDescription = "返回",
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * 底栏，对应 CSS `.bottom-nav`：5 等分格，`概览 / 记录 / 记一笔 / 车辆 / 设置`。
 *
 * 选中态是一块 `33×27` 的圆角色块（`.active::before`），用缩放 + 淡入替换生硬的显示/隐藏；
 * 标签颜色与字重同时过渡，所以手指还在滑动时指示就已经跟上页码了。
 */
@Composable
private fun YouHaoBottomBar(
    current: MainSection,
    onSelect: (MainSection) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = RectangleShape,
                clip = false,
                ambientColor = softShadowColor(),
                spotColor = softShadowColor(),
            )
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainSection.entries.forEachIndexed { index, entry ->
                if (index == 2) {
                    BottomAddButton(onClick = onAdd, modifier = Modifier.weight(1f))
                }
                BottomTab(
                    section = entry,
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomTab(
    section: MainSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `.active { color: var(--accent-dark); font-weight: 600 }`
    val activeColor = MaterialTheme.colorScheme.onPrimaryContainer
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor by animateColorAsState(
        targetValue = if (selected) activeColor else idleColor,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "tab-color",
    )
    val indicator by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "tab-indicator",
    )
    val chip = accentChipColor()

    Column(
        modifier = modifier.pressable(onClick = onClick, scaleTo = 0.94f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 33.dp, height = 27.dp)
                .graphicsLayer {
                    alpha = indicator
                    scaleX = 0.72f + 0.28f * indicator
                    scaleY = 0.72f + 0.28f * indicator
                }
                .clip(RoundedCornerShape(10.dp))
                .background(chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = sectionIcon(section)),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = contentColor,
            )
        }
        Text(
            text = section.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** `.bottom-add`：`39×34` 青柠块，圆角 `11px 11px 11px 4px`，标签「记一笔」。 */
@Composable
private fun BottomAddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.pressable(onClick = onClick, scaleTo = 0.92f, onClickLabel = "记一笔"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 39.dp, height = 34.dp)
                .shadow(
                    elevation = 7.dp,
                    shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp, bottomEnd = 11.dp, bottomStart = 4.dp),
                    clip = false,
                    ambientColor = softShadowColor(),
                    spotColor = softShadowColor(),
                )
                .clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp, bottomEnd = 11.dp, bottomStart = 4.dp))
                .background(Signal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = SapInk,
            )
        }
        Text(
            text = "记一笔",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@DrawableRes
private fun sectionIcon(section: MainSection): Int = when (section) {
    MainSection.OVERVIEW -> R.drawable.ic_nav_overview
    MainSection.RECORDS -> R.drawable.ic_nav_records
    MainSection.VEHICLES -> R.drawable.ic_nav_vehicles
    MainSection.SETTINGS -> R.drawable.ic_nav_settings
}

private fun titleFor(route: String?): String = when {
    route == null -> "油迹"
    route == Routes.RECORD_NEW -> "新增加油记录"
    route == Routes.RECORD_EDIT -> "编辑加油记录"
    route == Routes.CONFLICTS -> "同步冲突"
    else -> "油迹"
}
