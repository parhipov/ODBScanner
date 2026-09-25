package com.odbscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.odbscanner.ObdManager

@Composable
fun SessionsScreen(m: ObdManager) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh) { m.sessions.list() }
    val current = m.session?.takeIf { !it.closed }?.dir
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item { Muted("Каждое подключение — отдельная сессия: raw.log (обмен с адаптером), data.csv (все значения), report.txt (сводка), scan.csv (GM-скан).") }
        items(list, key = { it.name }) { dir ->
            Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dir.name + if (dir == current) "  · запись" else "", style = MaterialTheme.typography.titleSmall)
                        Text("%.1f КБ".format(m.sessions.size(dir) / 1024.0), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        if (dir == current) m.session?.flush()
                        shareFile(ctx, m.sessions.zip(dir))
                    }) { Icon(Icons.Default.Share, "Отправить") }
                    IconButton(onClick = {
                        if (dir == current) toast(ctx, "Сессия ещё пишется") else { m.sessions.delete(dir); refresh++ }
                    }) { Icon(Icons.Default.Delete, "Удалить") }
                }
            }
        }
        if (list.isEmpty()) item { Muted("Сессий пока нет.") }
    }
}
