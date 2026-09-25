package com.odbscanner.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.odbscanner.ObdManager
import com.odbscanner.VehicleInfo
import com.odbscanner.obd.DtcKind
import com.odbscanner.obd.Mode09
import com.odbscanner.obd.Pids
import com.odbscanner.obd.Reading
import com.odbscanner.obd.ecuName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every live value, grouped by ECU. */
@Composable
fun AllScreen(r: Map<String, Reading>, v: VehicleInfo) {
    val groups = r.values.groupBy { it.ecu }.toSortedMap(compareBy { if (it == 0) Int.MAX_VALUE else it })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Muted("Опрашиваются все поддерживаемые PID: ${v.supported01.count { !Pids.isBitmask(it) }} шт. Всего значений: ${r.size}.")
        }
        for ((ecu, list) in groups) {
            item(key = "h$ecu") { SectionTitle("${ecuName(ecu)} · %03X".format(ecu)) }
            items(list.sortedBy { it.source }, key = { it.key }) { ReadingRow(it) }
        }
    }
}

@Composable
fun DtcScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Row(Modifier.padding(8.dp)) {
                Button(onClick = { m.refreshDtc() }, enabled = busy == null) { Text("Прочитать") }
                OutlinedButton(onClick = { confirm = true }, enabled = busy == null, modifier = Modifier.padding(start = 8.dp)) { Text("Сбросить…") }
            }
            if (busy != null) Muted("Выполняется: $busy")
            if (v.dtcTime > 0) Muted("Прочитано в ${fmt.format(Date(v.dtcTime))}")
        }
        for (kind in DtcKind.entries) {
            val list = v.dtcs.filter { it.kind == kind }
            item(key = kind.name) {
                SectionTitle("${kind.title} (${list.size})")
                if (list.isEmpty()) Muted("нет")
                for (d in list) ValueRow(d.code, ecuName(d.ecu), "", d.description, if (kind == DtcKind.PENDING) Warn else Bad)
            }
        }
        item {
            SectionTitle("Стоп-кадр" + (v.freezeDtc?.let { " — $it" } ?: ""))
            if (v.freeze.isEmpty()) Muted("нет")
            for (f in v.freeze) ReadingRow(f)
        }
        item {
            SectionTitle("Все блоки GM (\$A9)")
            Muted("Полная память ошибок каждого блока на HS-CAN: ECM, TCM, ABS, BCM и др., включая коды без Check и тип отказа (как в GDS2). " +
                "Только чтение. Сначала ищутся модули (~1 мин), дальше несколько секунд на блок. Зажигание включено, машина стоит.")
            Row(Modifier.padding(8.dp)) {
                Button(onClick = { m.readAllModulesDtc() }, enabled = busy == null) { Text("Прочитать все блоки") }
            }
            if (v.gmDtcStatus.isNotEmpty()) Muted(v.gmDtcStatus)
            if (v.gmDtcTime > 0) Muted("Прочитано в ${fmt.format(Date(v.gmDtcTime))}")
        }
        for (r in v.gmDtcs) {
            item(key = "gm${r.module.id}") {
                SectionTitle("${r.module.name} · ${r.module.id}")
                Muted(r.result)
                for (c in r.codes) ValueRow(c.full, if (c.current) "активна" else "история", "", "${c.description} · ${c.flags}",
                    if (c.current || c.mil) Bad else Warn)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Сбросить ошибки?") },
        text = { Text("Будут стёрты коды, стоп-кадр и готовность мониторов (Mode 04). Зажигание включено, двигатель заглушен. Перед сбросом все коды уже записаны в сессию.") },
        confirmButton = { TextButton(onClick = { confirm = false; m.clearDtc() }) { Text("Сбросить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
}

@Composable
fun InfoScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            SectionTitle("Адаптер")
            ValueRow("Версия", v.adapter.ifEmpty { "—" })
            ValueRow("Описание", v.adapterDesc.ifEmpty { "—" })
            ValueRow("Протокол", v.protocol.ifEmpty { "—" })
            ValueRow("Мульти-PID запросы", if (v.multiPid) "да" else "нет")
            SectionTitle("Автомобиль")
            ValueRow("VIN", v.vin ?: "—")
            Row(Modifier.padding(8.dp)) {
                OutlinedButton(onClick = { m.rediscover() }, enabled = busy == null) { Text("Опросить заново") }
                OutlinedButton(onClick = { m.refreshMode06() }, enabled = busy == null, modifier = Modifier.padding(start = 8.dp)) { Text("Mode 06") }
            }
        }
        for (e in v.ecus.values.sortedBy { it.header }) {
            item(key = "ecu${e.header}") {
                SectionTitle("${ecuName(e.header)} · %03X".format(e.header))
                ValueRow("PID Mode 01", "${e.pids01.count { !Pids.isBitmask(it) }}")
                for ((t, s) in e.info09) ValueRow(Mode09.name(t), if (t == 0x08 || t == 0x0B) "" else s, sub = if (t == 0x08 || t == 0x0B) s else null)
                if (e.readiness.isNotEmpty()) {
                    Muted("Готовность мониторов:")
                    for (mon in e.readiness.filter { it.available }) {
                        ValueRow(mon.name, if (mon.complete) "готов" else "не готов", color = if (mon.complete) Good else Warn)
                    }
                }
            }
        }
        if (v.mode06.isNotEmpty()) {
            item { SectionTitle("Бортовые тесты (Mode 06): ${v.mode06.size}") }
            items(v.mode06, key = { "%03X.%02X.%02X".format(it.ecu, it.mid, it.tid) }) { t ->
                ValueRow("${t.midName}", "${Reading.fmt(t.value, 3)}", t.unit,
                    if (t.notRun) "${t.tidName} · не выполнялся" else "${t.tidName} · норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}",
                    if (t.notRun) MaterialTheme.colorScheme.outline else if (t.passed) Good else Bad)
                HorizontalDivider()
            }
        }
    }
}
