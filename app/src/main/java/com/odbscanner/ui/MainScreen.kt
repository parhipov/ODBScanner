package com.odbscanner.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.odbscanner.VehicleInfo
import com.odbscanner.obd.Reading
import com.odbscanner.obd.pick

private val MAIN_TILES = listOf(
    "01.0C" to "Обороты",
    "01.0D" to "Скорость",
    "01.05" to "ОЖ",
    "22.1940" to "Масло АКПП",
    "22.199A" to "Передача",
    "22.1991" to "Проскальз. ГТ",
    "01.5C" to "Масло двигателя",
    "22.1154" to "Масло двигателя",
    "22.1470" to "Давление масла",
    "22.119F" to "Ресурс масла",
    "01.04" to "Нагрузка",
    "01.11" to "Дроссель",
    "01.49" to "Педаль газа",
    "01.0E" to "Опережение",
    "01.10" to "MAF",
    "01.0B" to "MAP",
    "01.0F" to "Воздух на впуске",
    "01.46" to "За бортом",
    "01.42" to "Бортсеть (ЭБУ)",
    "000:ATRV" to "Бортсеть (адаптер)",
    "01.2F" to "Топливо в баке",
    "calc.l100" to "Расход",
    "calc.lph" to "Расход, л/ч",
    "calc.trim1" to "Коррекция Б1",
    "calc.trim2" to "Коррекция Б2",
    "01.33" to "Атм. давление",
    "01.1F" to "С момента пуска",
)

@Composable
fun MainScreen(r: Map<String, Reading>, v: VehicleInfo) {
    val mil = r.pick("01.01.MIL")?.value
    val dtcCount = r.pick("01.01.DTC")?.value?.toInt()
    val tiles = MAIN_TILES.mapNotNull { (k, label) ->
        val reading = if (k.contains(':')) r[k] else r.pick(k)
        reading?.let { label to it }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(165.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                mil == 1.0 -> Hint("Check Engine горит · ошибок в памяти: ${dtcCount ?: "?"} — см. вкладку «Ошибки»", Bad)
                (dtcCount ?: 0) > 0 || v.dtcs.isNotEmpty() -> Hint("Есть коды ошибок: ${v.dtcs.size} — см. вкладку «Ошибки»", Warn)
                v.step.isNotEmpty() -> Hint("Опрос автомобиля: ${v.step}", Good)
            }
        }
        items(tiles, key = { it.second.key }) { (label, reading) ->
            val color = when (reading.source) {
                "calc.trim1", "calc.trim2" -> trimColor(reading.value)
                "01.05" -> reading.value?.let { if (it > 105) Bad else if (it < 70) Warn else null }
                "22.1940" -> reading.value?.let { if (it > 110) Bad else if (it > 95) Warn else null }
                "22.1154" -> reading.value?.let { if (it > 135) Bad else if (it > 120) Warn else null }
                "22.119F" -> reading.value?.let { if (it < 10) Bad else if (it < 25) Warn else null }
                else -> null
            }
            ValueTile(label, reading, color = color)
        }
        if (tiles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Muted("Нет данных — подключитесь к адаптеру на вкладке «Связь».") }
    }
}
