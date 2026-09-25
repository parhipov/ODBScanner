package com.odbscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.odbscanner.ObdManager
import com.odbscanner.VehicleInfo
import com.odbscanner.obd.Reading
import com.odbscanner.obd.pick
import kotlin.math.abs

@Composable
fun FuelScreen(m: ObdManager, r: Map<String, Reading>, v: VehicleInfo) {
    fun p(k: String) = r.pick(k)
    val t1 = p("calc.trim1")?.value
    val t2 = p("calc.trim2")?.value
    val misfire = v.mode06.filter { it.misfireCylinder != null && it.tid == 0x0C }.sortedBy { it.misfireCylinder }
    val misfireAvg = v.mode06.filter { it.misfireCylinder != null && it.tid == 0x0B }.associateBy { it.misfireCylinder }
    val fuelDtcs = v.dtcs.filter { it.fuelRelated }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            for (h in hints(t1, t2, p("01.03")?.text, misfire.map { it.misfireCylinder!! to it.value }, fuelDtcs.map { it.code })) {
                Hint(h.first, h.second)
            }
        }

        item {
            SectionTitle("Топливные коррекции")
            BankCard("Банк 1 (цил. 1-3-5)", p("01.06"), p("01.07"), p("calc.trim1"))
            BankCard("Банк 2 (цил. 2-4-6)", p("01.08"), p("01.09"), p("calc.trim2"))
            p("calc.trimDiff")?.let { ReadingRow(it, trimColor(it.value)) }
            listOf("01.55.A", "01.56.A", "01.57.A", "01.58.A").mapNotNull { p(it) }.forEach { ReadingRow(it) }
        }

        item {
            SectionTitle("Смесь и датчики кислорода")
            rows(r, "01.03.S", "01.03", "01.44")
            r.values.filter { it.source.matches(Regex("01\\.(1[4-9A-B]|2[4-9A-B]|3[4-9A-B])\\..")) }
                .sortedBy { it.source }.forEach { ReadingRow(it) }
        }

        item {
            SectionTitle("Пропуски зажигания (Mode 06)")
            if (misfire.isEmpty()) Muted("ЭБУ не отдаёт счётчики пропусков по цилиндрам (или ещё не прочитаны).")
            Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    for (t in misfire) {
                        val n = t.value
                        Column(Modifier.weight(1f)) {
                            Text("Ц${t.misfireCylinder}", style = MaterialTheme.typography.labelMedium)
                            Text(Reading.fmt(n, 0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                                color = if (n >= 20) Bad else if (n > 0) Warn else Good)
                            misfireAvg[t.misfireCylinder]?.let { Text("ср. ${Reading.fmt(it.value, 0)}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            Row {
                OutlinedButton(onClick = { m.refreshMode06() }, Modifier.padding(8.dp)) { Text("Обновить сейчас") }
                Muted("Цикл = текущая/последняя поездка. На этой вкладке обновляется каждые 15 с.")
            }
        }

        item {
            SectionTitle("Зажигание и детонация")
            rows(r, "01.0E", "22.11A6", "22.125D", "22.12D9", "22.125E", "22.119E")
            SectionTitle("Нагрузка и воздух")
            rows(r, "01.04", "01.43", "01.10", "01.0B", "01.0F", "01.0C", "01.33")
        }

        val gmCyl = GM_CYL.map { (label, dids) -> label to dids.map { r.pick(it) } }.filter { (_, v) -> v.any { it != null } }
        if (gmCyl.isNotEmpty()) item {
            SectionTitle("По цилиндрам (GM)")
            Muted("Параметры GM с форумов; «(?)» — формула из одного источника.")
            Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(8.dp)) {
                    Row { Text("", Modifier.weight(1.6f)); for (c in 1..6) Text("Ц$c", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) }
                    for ((label, vals) in gmCyl) Row {
                        Text(label, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                        for (v in vals) Text(v?.display() ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (label.startsWith("Пропуски") && (v?.value ?: 0.0) > 0) Warn else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        item {
            SectionTitle("Расход и топливо")
            rows(r, "calc.lph", "calc.l100", "01.5E", "01.9D.E", "01.A2", "01.2F", "01.51.S", "01.51", "01.52",
                "01.0A", "01.22", "01.23", "01.59", "01.5D")
        }

        item {
            SectionTitle("Катализаторы и EVAP")
            rows(r, "01.3C", "01.3D", "01.3E", "01.3F", "01.2E", "01.32", "01.53", "01.54")
            val cat = v.mode06.filter { it.mid in 0x21..0x24 }
            for (t in cat) ValueRow("${t.midName}: ${t.tidName}", Reading.fmt(t.value, 3), t.unit,
                if (t.notRun) "не выполнялся" else "норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}",
                if (t.notRun) MaterialTheme.colorScheme.outline else if (t.passed) Good else Bad)
        }

        if (fuelDtcs.isNotEmpty()) item {
            SectionTitle("Ошибки, связанные с топливом")
            for (d in fuelDtcs) ValueRow(d.code, d.kind.title, "", d.description, Bad)
        }
        item { Gap() }
    }
}

private val GM_CYL = listOf(
    "Пропуски сейчас" to listOf("22.1206", "22.1205", "22.1207", "22.1208", "22.11EA", "22.11EB"),
    "Пропуски история" to listOf("22.1201", "22.1202", "22.1203", "22.1204", "22.11F8", "22.11F9"),
    "Впрыск, мс" to (1..6).map { "22.%04X".format(0x1192 + it) },
    "Баланс" to (1..6).map { "22.%04X".format(0x162E + it) },
)

@Composable
private fun BankCard(title: String, st: Reading?, lt: Reading?, sum: Reading?) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { ValueRow("Кратк.", st?.display() ?: "—", "%", color = trimColor(st?.value)) }
                Column(Modifier.weight(1f)) { ValueRow("Долг.", lt?.display() ?: "—", "%", color = trimColor(lt?.value)) }
                Column(Modifier.weight(1f)) { ValueRow("Сумма", sum?.display() ?: "—", "%", color = trimColor(sum?.value)) }
            }
            TrimBar(sum?.value)
            if (lt?.min != null && lt.max != null) Muted("Долгосрочная за сессию: ${Reading.fmt(lt.min, 1)}…${Reading.fmt(lt.max, 1)} %")
        }
    }
}

@Composable
private fun rows(r: Map<String, Reading>, vararg keys: String) {
    for (k in keys) r.pick(k)?.let { ReadingRow(it) }
}

private fun hints(
    t1: Double?, t2: Double?, fuelStatus: String?,
    misfires: List<Pair<Int, Double>>, fuelDtcs: List<String>,
): List<Pair<String, androidx.compose.ui.graphics.Color>> {
    val out = mutableListOf<Pair<String, androidx.compose.ui.graphics.Color>>()
    if (fuelStatus != null && fuelStatus.contains("разомкнутый")) {
        out += "Топливная система в разомкнутом режиме ($fuelStatus) — коррекции сейчас не показательны." to Warn
    }
    if (t1 != null && t2 != null) {
        val lean1 = t1 > 10; val lean2 = t2 > 10
        val rich1 = t1 < -10; val rich2 = t2 < -10
        when {
            lean1 && lean2 -> out += "Оба банка обеднены (+${Reading.fmt(t1, 0)}% / +${Reading.fmt(t2, 0)}%): подсос воздуха, слабый бензонасос/фильтр, грязный MAF." to Bad
            rich1 && rich2 -> out += "Оба банка обогащены: давление топлива, подтекающие форсунки, MAF завышает, адсорбер." to Bad
            lean1 || lean2 -> out += "Обеднён только банк ${if (lean1) 1 else 2}: подсос на этом банке (прокладка впуска), форсунка, датчик O2." to Warn
            rich1 || rich2 -> out += "Обогащён только банк ${if (rich1) 1 else 2}: форсунка льёт, датчик O2 этого банка." to Warn
            abs(t1 - t2) > 8 -> out += "Банки расходятся на ${Reading.fmt(abs(t1 - t2), 0)}% — стоит проверить впуск и форсунки." to Warn
            abs(t1) < 5 && abs(t2) < 5 -> out += "Коррекции в норме — смесь в порядке." to Good
        }
    }
    val bad = misfires.filter { it.second > 0 }
    if (bad.isNotEmpty()) {
        out += "Пропуски зажигания: " + bad.joinToString { "Ц${it.first}: ${Reading.fmt(it.second, 0)}" } +
            ". Если по всем цилиндрам — топливо/давление; если по одному — свеча/катушка/форсунка." to if (bad.any { it.second >= 20 }) Bad else Warn
    }
    if (fuelDtcs.isNotEmpty()) out += "Коды по топливу/смеси: ${fuelDtcs.joinToString()}" to Bad
    return out
}
