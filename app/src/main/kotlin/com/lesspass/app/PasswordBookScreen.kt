package com.lesspass.app


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.lesspass.app.data.PasswordEntry

@Composable
fun PasswordBookScreen(
    dbManager: DatabaseManager,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<PasswordEntry>()) }

    LaunchedEffect(dbManager.unlocked) {
        entries = dbManager.getPasswordBookEntries().map { entry ->
            PasswordEntry(
                title = entry.title,
                username = entry.username,
                password = String(entry.password),
                masterPassword = dbManager.getMasterPasswordFromEntry(entry),
                url = entry.url,
                notes = entry.notes,
            )
        }
    }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("密码本", style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("手动添加")
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("密码本为空，生成密码时点击保存即可添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.uuid.toString() }) { entry ->
                    PasswordBookCard(
                        entry = entry,
                        onCopy = { onCopy(entry.password) },
                        onDelete = {
                            dbManager.deleteEntry(
                                dbManager.getPasswordBookEntries().firstOrNull { e ->
                                    e.title == entry.title && e.username == entry.username
                                } ?: return@PasswordBookCard
                            )
                            dbManager.saveDatabase()
                            entries = dbManager.getPasswordBookEntries().map { e ->
                                PasswordEntry(
                                    title = e.title,
                                    username = e.username,
                                    password = String(e.password),
                                    masterPassword = dbManager.getMasterPasswordFromEntry(e),
                                    url = e.url,
                                    notes = e.notes,
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddEntryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, username, password, url, notes ->
                dbManager.addPasswordBookEntry(
                    title = title,
                    username = username,
                    password = password,
                    url = url,
                    notes = notes
                )
                dbManager.saveDatabase()
                entries = dbManager.getPasswordBookEntries().map { e ->
                    PasswordEntry(
                        title = e.title,
                        username = e.username,
                        password = String(e.password),
                        masterPassword = dbManager.getMasterPasswordFromEntry(e),
                        url = e.url,
                        notes = e.notes,
                    )
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun PasswordBookCard(
    entry: PasswordEntry,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "复制") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            }
            Text(
                text = entry.password,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
            entry.masterPassword.takeIf { it.isNotEmpty() }?.let { mp ->
                var showMp by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "主密码: ${if (showMp) mp else "•".repeat(mp.length.coerceAtLeast(6))}",
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
        }
    }
}
