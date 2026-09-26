package com.obdscanner.ui

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obdscanner.R
import com.obdscanner.obd.Make

/**
 * A car the guide has its own section for: button label, that section and the picture of where its
 * OBD socket is (res/values/guide.xml, res/drawable-nodpi/obd_*.png from art/obd/make_obd_art.py).
 */
private class GuideCar(val make: Make, @StringRes val label: Int, @ArrayRes val section: Int, @DrawableRes val obd: Int, @StringRes val obdText: Int)

private val CARS = listOf(
    GuideCar(Make.GM, R.string.guide_make_gm, R.array.guide_gm, R.drawable.obd_cts, R.string.guide_obd_gm),
    GuideCar(Make.VAG, R.string.guide_make_vag, R.array.guide_vag, R.drawable.obd_polo, R.string.guide_obd_vag),
    GuideCar(Make.TOYOTA, R.string.guide_make_toyota, R.array.guide_toyota, R.drawable.obd_rav4, R.string.guide_obd_toyota),
    GuideCar(Make.LADA, R.string.guide_make_lada, R.array.guide_lada, R.drawable.obd_vesta, R.string.guide_obd_lada),
    GuideCar(Make.HYUNDAI, R.string.guide_make_hyundai, R.array.guide_hyundai, R.drawable.obd_solaris, R.string.guide_obd_hyundai),
)

/** Detail sections under the short steps, in order. */
private fun detailSections(car: GuideCar): List<Int> =
    listOf(R.array.guide_more_steps, car.section, R.array.guide_safety, R.array.guide_files)

@Composable
fun GuideScreen(make: Make) {
    // Follows the car once its VIN is known; the buttons switch it by hand.
    var picked by rememberSaveable { mutableStateOf<Make?>(null) }
    val car = CARS.firstOrNull { it.make == (picked ?: make) } ?: CARS.first()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guide_for), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (make != Make.OTHER) Text(stringResource(R.string.guide_detected, make.title),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            for (row in CARS.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    for ((i, c) in row.withIndex()) {
                        MakeButton(stringResource(c.label), c == car, Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 4.dp, end = if (i == 0) 4.dp else 0.dp)) {
                            picked = c.make
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f).padding(start = 4.dp))
                }
            }
        }
        item {
            val steps = stringArrayResource(R.array.guide_steps)
            Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(steps.first(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    for ((i, s) in steps.drop(1).withIndex()) {
                        val last = i == steps.size - 2
                        Text(s, style = if (last) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = if (last) Warn else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = if (last) 10.dp else 6.dp))
                    }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.guide_obd_title))
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Image(painterResource(car.obd), contentDescription = stringResource(car.obdText),
                    modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text(stringResource(car.obdText), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            Muted(stringResource(R.string.guide_obd_note))
        }
        item {
            Text(stringResource(R.string.guide_details), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 6.dp, top = 18.dp))
        }
        items(detailSections(car)) { GuideSection(it) }
    }
}

@Composable
private fun MakeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = @Composable { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (selected) Button(onClick = onClick, modifier = modifier) { label() }
    else OutlinedButton(onClick = onClick, modifier = modifier) { label() }
}

@Composable
private fun GuideSection(@ArrayRes id: Int) {
    val lines = stringArrayResource(id)
    SectionTitle(lines.first())
    for (p in lines.drop(1)) {
        Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
