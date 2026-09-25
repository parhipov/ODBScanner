package com.odbscanner.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.odbscanner.obd.Reading
import java.io.File
import kotlin.math.abs

val Good = Color(0xFF4CAF50)
val Warn = Color(0xFFFFB300)
val Bad = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7AB8FF),
            secondary = Color(0xFFB0C4DE),
            background = Color(0xFF0E1116),
            surface = Color(0xFF0E1116),
            surfaceVariant = Color(0xFF1B2029),
        ),
        content = content,
    )
}

/** Big dashboard tile. */
@Composable
fun ValueTile(label: String, r: Reading?, modifier: Modifier = Modifier, color: Color? = null) {
    Card(
        modifier = modifier.padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    r?.display() ?: "—",
                    fontSize = if ((r?.display()?.length ?: 1) > 8) 18.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                if (r?.value != null && r.unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(r.unit, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            // Always reserve the min/max line so tiles in a grid row keep the same height.
            val range = if (r?.value != null && r.min != null && r.max != null && r.min != r.max)
                "мин ${Reading.fmt(r.min, r.decimals)} · макс ${Reading.fmt(r.max, r.decimals)}" else " "
            Text(range, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
}

/** Compact "name ........ value unit" row. */
@Composable
fun ValueRow(name: String, value: String, unit: String = "", sub: String? = null, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface, fontFamily = if (value.length > 12 && value.all { it.isLetterOrDigit() && it.code < 128 || it == ' ' }) FontFamily.Monospace else null)
        if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ReadingRow(r: Reading, color: Color? = null) {
    val range = if (r.value != null && r.min != null && r.max != null && r.min != r.max)
        "${r.source} · ${Reading.fmt(r.min, r.decimals)}…${Reading.fmt(r.max, r.decimals)}" else r.source
    ValueRow(r.name, r.display(), if (r.value != null) r.unit else "", range, color)
}

/** Horizontal bar centered at zero, for fuel trims (±25%). */
@Composable
fun TrimBar(value: Double?, limit: Double = 25.0) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (value != null) {
            val frac = (abs(value) / limit).coerceIn(0.0, 1.0).toFloat() / 2f
            val c = trimColor(value) ?: Good
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                if (value < 0) {
                    Spacer(Modifier.weight(0.5f - frac + 0.0001f))
                    Box(Modifier.weight(frac + 0.0001f).fillMaxHeight().background(c))
                    Spacer(Modifier.weight(0.5f))
                } else {
                    Spacer(Modifier.weight(0.5f))
                    Box(Modifier.weight(frac + 0.0001f).fillMaxHeight().background(c))
                    Spacer(Modifier.weight(0.5f - frac + 0.0001f))
                }
            }
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
    }
}

fun trimColor(v: Double?): Color? = when {
    v == null -> null
    abs(v) >= 15 -> Bad
    abs(v) >= 8 -> Warn
    else -> Good
}

@Composable
fun Hint(text: String, color: Color = Warn) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
fun Gap() = Spacer(Modifier.height(8.dp))

val spaced = Arrangement.spacedBy(8.dp)

fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ODB Scanner: ${file.nameWithoutExtension}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Отправить сессию").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
