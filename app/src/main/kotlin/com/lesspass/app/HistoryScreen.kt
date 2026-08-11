package com.lesspass.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.lesspass.app.data.DatabaseManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    dbManager: DatabaseManager,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<EntryKDBX>()) }

    LaunchedEffect(dbManager.unlocked) {
        entries = dbManager.getHistoryEntries()
    }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无历史记录，去生成一个密码吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { e -> e.id.toString() }) { entry ->
            HistoryCard(
                entry = entry,
                dbManager = dbManager,
                onCopy = { onCopy(String(entry.password)) },
                onDelete = {
                    dbManager.deleteHistoryEntry(entry)
                    dbManager.saveDatabase()
                    entries = dbManager.getHistoryEntries()
                }
            )
        }
    }
}

@Composable
private fun HistoryCard(
    entry: EntryKDBX,
    dbManager: DatabaseManager,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMp by remember { mutableStateOf(false) }
    val masterPassword = dbManager.getMasterPasswordFromEntry(entry)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.url, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = entry.username.ifEmpty { "无登录名" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
            Text(
                text = String(entry.password),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
            // 主密码：默认掩码，点击眼睛按钮切换明文
            if (masterPassword.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "主密码: ${if (showMp) masterPassword else "•".repeat(masterPassword.length.coerceAtLeast(6))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showMp = !showMp }) {
                        Icon(
                            if (showMp) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showMp) "隐藏主密码" else "显示主密码"
                        )
                    }
                }
            }
            Text(
                text = formatTime(entry.creationTime.toMilliseconds()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
