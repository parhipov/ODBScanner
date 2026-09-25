package com.odbscanner.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.odbscanner.ObdManager
import com.odbscanner.BusState
import com.odbscanner.ScanState
import com.odbscanner.gm.GmModule
import com.odbscanner.gm.GmModules
import com.odbscanner.obd.Reading

@Composable
fun GmScreen(m: ObdManager, s: ScanState, bus: BusState, r: Map<String, Reading>, busy: String?, connected: Boolean) {
    var rangeIdx by remember { mutableIntStateOf(0) }
    var rangeMenu by remember { mutableStateOf(false) }
    var onlyWatched by remember { mutableStateOf(false) }
    val hits = if (onlyWatched) s.hits.filter { it.key in s.watched } else s.hits

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Hint("Старый ELM327 видит только HS-CAN: ECM, TCM, ABS, BCM и др. Подушки, приборка, радио, климат, TPMS — на однопроводной GMLAN (пин 1), для них нужен OBDLink MX+.", Warn)
            Hint("Только чтение (сервисы \$1A и \$22). Сканировать на стоящей машине: зажигание включено или двигатель на ХХ. " +
                "Найденные DID-ы пишутся в scan.csv сессии. Отметьте ☑ интересные — они будут опрашиваться, и по логу можно понять, что это за параметр.")
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.probeModules() }, enabled = connected && busy == null) { Text("Найти модули") }
                if (busy != null) OutlinedButton(onClick = { m.stopOp() }, Modifier.padding(start = 8.dp)) { Text("Стоп") }
            }
            if (s.running || s.status.isNotEmpty()) {
                if (s.running) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Muted(s.status)
            }
            SectionTitle("Прослушка шины")
            Muted("Слушает обычный обмен между блоками (ничего не отправляет в блоки). ~10 с обзор, потом по 1.5 с на каждый ID — обычно 1–2 минуты. Лучше с заведённым двигателем; можно погазовать. Пишется в bus.csv.")
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.sniffBus() }, enabled = connected && busy == null) { Text("Слушать шину") }
            }
            if (bus.running || bus.status.isNotEmpty()) {
                if (bus.running) LinearProgressIndicator(progress = { bus.progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Muted(bus.status)
            }
            for (b in bus.ids) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(b.idHex, Modifier.weight(0.7f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(if (b.hz > 0) "%.0f Гц".format(b.hz) else "—", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                    Text(b.lastHex, Modifier.weight(3f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionTitle("Диапазон для \$22")
            Row(Modifier.padding(horizontal = 8.dp)) {
                OutlinedButton(onClick = { rangeMenu = true }) { Text(GmModules.ranges22[rangeIdx].first) }
                DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                    GmModules.ranges22.forEachIndexed { i, (label, _) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { rangeIdx = i; rangeMenu = false })
                    }
                }
            }
            SectionTitle("Модули на HS-CAN (${s.modules.size})")
            if (s.modules.isEmpty()) Muted("Нажмите «Найти модули». Опрос ~40 адресов, около минуты.")
        }
        items(s.modules, key = { it.id }) { mod ->
            ModuleCard(mod, enabled = connected && busy == null,
                onScan1A = { m.scanModule(mod, "1A", 0x00..0xFF) },
                onScan22 = { m.scanModule(mod, "22", GmModules.ranges22[rangeIdx].second) })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Найдено DID: ${s.hits.size}")
                Checkbox(checked = onlyWatched, onCheckedChange = { onlyWatched = it })
                Text("только отмеченные")
            }
        }
        items(hits, key = { it.key }) { h ->
            val live = r[h.key]
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = h.key in s.watched, onCheckedChange = { m.toggleWatch(h) })
                Column(Modifier.weight(1f)) {
                    Text("%03X  %s %s  (%d байт)".format(h.req, h.service, h.didHex, h.data.size) + (h.label?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelLarge)
                    Text(live?.text ?: h.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    h.partNumber?.let { Text("№ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    if (h.looksLikeText) Text("«${h.ascii}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    if (live?.value != null) Text("A/AB = ${live.display()} (мин ${live.min?.let { Reading.fmt(it, 0) }}, макс ${live.max?.let { Reading.fmt(it, 0) }})",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(mod: GmModule, enabled: Boolean, onScan1A: () -> Unit, onScan22: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text("${mod.name}  ·  %03X → %03X".format(mod.req, mod.resp), style = MaterialTheme.typography.titleSmall)
            Text(mod.answeredTo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row {
                OutlinedButton(onClick = onScan1A, enabled = enabled) { Text("Скан \$1A (256)") }
                OutlinedButton(onClick = onScan22, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) { Text("Скан \$22") }
            }
        }
    }
}
