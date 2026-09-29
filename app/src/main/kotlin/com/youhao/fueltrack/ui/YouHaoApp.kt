package com.youhao.fueltrack.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.youhao.fueltrack.R
import com.youhao.fueltrack.ui.conflicts.ConflictsScreen
import com.youhao.fueltrack.ui.overview.OverviewScreen
import com.youhao.fueltrack.ui.records.RecordEditorScreen
import com.youhao.fueltrack.ui.records.RecordsScreen
import com.youhao.fueltrack.ui.settings.SettingsScreen
import com.youhao.fueltrack.ui.vehicles.VehiclesScreen
import kotlinx.coroutines.launch

enum class MainSection(val route: String, val label: String) {
    OVERVIEW("overview", "概览"), RECORDS("records", "记录"),
    VEHICLES("vehicles", "车辆"), SETTINGS("settings", "设置"),
}

object Routes {
    const val MAIN = "main"
    const val RECORD_NEW = "records/new?vehicleId={vehicleId}"
    const val RECORD_EDIT = "records/edit/{recordId}"
    const val CONFLICTS = "conflicts"
    fun recordEdit(id: String) = "records/edit/${android.net.Uri.encode(id)}"
    fun recordNew(vehicleId: String?) = "records/new" + (vehicleId?.let { "?vehicleId=${android.net.Uri.encode(it)}" } ?: "")
}

@Composable
fun YouHaoApp(modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onMain = route == null || route == Routes.MAIN
    val pager = rememberPagerState(pageCount = { MainSection.entries.size })
    val scope = rememberCoroutineScope()
    var requestedVehicle by rememberSaveable { mutableStateOf<String?>(null) }
    var recordRequest by rememberSaveable { mutableIntStateOf(0) }
    val addRecord: (String?) -> Unit = { navController.navigate(Routes.recordNew(it)) { launchSingleTop = true } }

    BackHandler(onMain && pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    Scaffold(
        modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                if (!onMain) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "返回")
                    }
                    Text(when (route) {
                        Routes.RECORD_NEW -> "记一笔加油"
                        Routes.RECORD_EDIT -> "编辑加油记录"
                        else -> "同步冲突"
                    }, style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        bottomBar = {
            if (onMain) Column(Modifier.background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.navigationBars)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MainSection.entries.forEachIndexed { index, section ->
                        val selected = pager.currentPage == index
                        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier.weight(1f).heightIn(min = 56.dp)
                                .selectable(selected, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(index) } })
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(painterResource(sectionIcon(section)), null, tint = tint, modifier = Modifier.size(21.dp))
                            Text(section.label, color = tint, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, Routes.MAIN, Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.MAIN) {
                HorizontalPager(pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { MainSection.entries[it].route }) { page ->
                    when (MainSection.entries[page]) {
                        MainSection.OVERVIEW -> OverviewScreen(
                            onOpenRecord = { navController.navigate(Routes.recordEdit(it)) },
                            onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                            onAddRecord = addRecord,
                            onShowRecords = { vehicle -> requestedVehicle = vehicle; recordRequest++; scope.launch { pager.animateScrollToPage(1) } },
                            isActive = pager.settledPage == page,
                        )
                        MainSection.RECORDS -> RecordsScreen(
                            onAddRecord = addRecord,
                            onOpenRecord = { navController.navigate(Routes.recordEdit(it)) },
                            requestedVehicleId = requestedVehicle, requestKey = recordRequest, isActive = pager.settledPage == page,
                        )
                        MainSection.VEHICLES -> VehiclesScreen()
                        MainSection.SETTINGS -> SettingsScreen(onOpenConflicts = { navController.navigate(Routes.CONFLICTS) })
                    }
                }
            }
            composable(Routes.RECORD_NEW, arguments = listOf(navArgument("vehicleId") { type = NavType.StringType; nullable = true; defaultValue = null })) { backStack ->
                RecordEditorScreen(recordId = null, vehicleId = backStack.arguments?.getString("vehicleId"), onDone = { navController.popBackStack() })
            }
            composable(Routes.RECORD_EDIT) { backStack ->
                RecordEditorScreen(recordId = backStack.arguments?.getString("recordId"), onDone = { navController.popBackStack() })
            }
            composable(Routes.CONFLICTS) { ConflictsScreen(onDone = { navController.popBackStack() }) }
        }
    }
}

@DrawableRes
private fun sectionIcon(section: MainSection): Int = when (section) {
    MainSection.OVERVIEW -> R.drawable.ic_nav_overview
    MainSection.RECORDS -> R.drawable.ic_nav_records
    MainSection.VEHICLES -> R.drawable.ic_nav_vehicles
    MainSection.SETTINGS -> R.drawable.ic_nav_settings
}
