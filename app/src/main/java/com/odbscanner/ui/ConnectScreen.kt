package com.odbscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.odbscanner.ConnState
import com.odbscanner.ObdManager

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(m: ObdManager, conn: ConnState) {
    val ctx = LocalContext.current
    val needPerm = Build.VERSION.SDK_INT >= 31
    fun granted() = !needPerm || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var hasPerm by remember { mutableStateOf(granted()) }
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = granted()
        refresh++
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (needPerm) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(wanted.toTypedArray())
        }
    }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val devices: List<BluetoothDevice> = remember(hasPerm, refresh) {
        if (!hasPerm || adapter == null || !adapter.isEnabled) emptyList()
        else adapter.bondedDevices.orEmpty().sortedWith(compareByDescending<BluetoothDevice> { it.address == m.lastDevice }
            .thenByDescending { looksLikeObd(it.name) }.thenBy { it.name ?: "" })
    }
    val busy = conn is ConnState.Connecting

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            when (conn) {
                is ConnState.Connecting -> Card(Modifier.fillMaxWidth().padding(4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp))
                        Column {
                            Text(conn.device, style = MaterialTheme.typography.titleMedium)
                            Text(conn.step)
                        }
                    }
                }
                is ConnState.Failed -> Hint("Ошибка: ${conn.message}\n\nЛог попытки сохранён в сессии — можно отправить кнопкой ⇪ сверху.", Bad)
                is ConnState.Connected -> Hint("Подключено: ${conn.device}", Good)
                ConnState.Idle -> Unit
            }
        }
        item {
            SectionTitle("Адаптер ELM327")
            when {
                adapter == null -> Muted("На устройстве нет Bluetooth.")
                !hasPerm -> Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }, Modifier.padding(8.dp)) {
                    Text("Разрешить доступ к Bluetooth")
                }
                !adapter.isEnabled -> Button(onClick = { enableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, Modifier.padding(8.dp)) {
                    Text("Включить Bluetooth")
                }
                devices.isEmpty() -> Muted("Нет спаренных устройств. Спарьте адаптер в настройках Bluetooth (PIN обычно 1234 или 0000).")
            }
        }
        items(devices, key = { it.address }) { d ->
            Card(
                Modifier.fillMaxWidth().padding(4.dp).clickable(enabled = !busy) { m.connectBluetooth(d) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(d.name ?: "Без имени", style = MaterialTheme.typography.titleMedium)
                    Text(d.address + if (d.address == m.lastDevice) " · последний" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(Modifier.padding(8.dp)) {
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Настройки Bluetooth") }
                OutlinedButton(onClick = { refresh++ }, Modifier.padding(start = 8.dp)) { Text("Обновить") }
            }
            SectionTitle("Без машины")
            Card(
                Modifier.fillMaxWidth().padding(4.dp).clickable(enabled = !busy) { m.connectDemo() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Демо-режим", style = MaterialTheme.typography.titleMedium)
                    Text("Эмулятор ELM327 + CTS 2.8: ECM, TCM, ошибки, Mode 06, GM-модули", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (conn is ConnState.Connected || busy) {
                Button(onClick = { m.disconnect() }, Modifier.padding(8.dp)) { Text("Отключиться") }
            }
            Muted("Старые клоны ELM327 работают только по классическому Bluetooth. Включите зажигание перед подключением.")
        }
    }
}

private fun looksLikeObd(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return listOf("obd", "elm", "v-link", "vgate", "konnwei", "icar", "scan").any { it in n }
}
