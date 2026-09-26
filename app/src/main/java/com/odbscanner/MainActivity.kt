package com.odbscanner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab as TabItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odbscanner.ui.AllScreen
import com.odbscanner.ui.AppTheme
import com.odbscanner.ui.ConnectScreen
import com.odbscanner.ui.DtcScreen
import com.odbscanner.ui.FuelScreen
import com.odbscanner.ui.GmScreen
import com.odbscanner.ui.GuideScreen
import com.odbscanner.ui.Good
import com.odbscanner.ui.InfoScreen
import com.odbscanner.ui.MainScreen
import com.odbscanner.ui.SessionsScreen
import com.odbscanner.ui.Warn
import com.odbscanner.ui.shareFile
import com.odbscanner.ui.toast

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val m = OdbApp.manager(this)
        setContent { AppTheme { AppRoot(m) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(m: ObdManager) {
    val ctx = LocalContext.current
    val conn by m.conn.collectAsStateWithLifecycle()
    val readings by m.readings.collectAsStateWithLifecycle()
    val vehicle by m.vehicle.collectAsStateWithLifecycle()
    val scan by m.scan.collectAsStateWithLifecycle()
    val busy by m.busy.collectAsStateWithLifecycle()
    val bus by m.bus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Connect) }

    LaunchedEffect(tab) { m.activeTab.value = tab }
    LaunchedEffect(conn) { if (conn is ConnState.Connected && tab == Tab.Connect) tab = Tab.Main }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ODB Scanner ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        val (status, color) = when (val c = conn) {
                            is ConnState.Connected -> "● ${c.device}${busy?.let { " · $it" } ?: ""}" to Good
                            is ConnState.Connecting -> "○ ${c.step}" to Warn
                            is ConnState.Failed -> "✕ ${c.message}" to MaterialTheme.colorScheme.error
                            ConnState.Idle -> "не подключено" to MaterialTheme.colorScheme.outline
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val dir = m.session?.also { it.flush() }?.dir ?: m.sessions.list().firstOrNull()
                        if (dir == null) toast(ctx, "Сессий пока нет") else shareFile(ctx, m.sessions.zip(dir))
                    }) { Icon(Icons.Default.Share, "Отправить сессию") }
                    if (conn is ConnState.Connected || conn is ConnState.Connecting) {
                        IconButton(onClick = { m.disconnect() }) { Icon(Icons.Default.Close, "Отключиться") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                Tab.entries.forEach { t ->
                    TabItem(selected = t == tab, onClick = { tab = t }, text = { Text(t.title) })
                }
            }
            when (tab) {
                Tab.Connect -> ConnectScreen(m, conn)
                Tab.Guide -> GuideScreen(vehicle.make)
                Tab.Main -> MainScreen(readings, vehicle)
                Tab.Fuel -> FuelScreen(m, readings, vehicle)
                Tab.All -> AllScreen(readings, vehicle)
                Tab.Dtc -> DtcScreen(m, vehicle, busy)
                Tab.Info -> InfoScreen(m, vehicle, busy)
                Tab.Gm -> GmScreen(m, scan, bus, readings, busy, conn is ConnState.Connected)
                Tab.Sessions -> SessionsScreen(m)
            }
        }
    }
}

