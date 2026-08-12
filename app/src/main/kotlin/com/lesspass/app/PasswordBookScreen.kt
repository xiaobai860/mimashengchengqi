package com.lesspass.app


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.lesspass.app.data.DatabaseManager
import com.lesspass.app.data.PasswordEntry

@Composable
fun PasswordBookScreen(
    dbManager: DatabaseManager,
    onCopy: (String) -> Unit,
    onViewHistory: (EntryKDBX) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<EntryKDBX>()) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun reload() {
        entries = dbManager.getPasswordBookEntries()
    }

    LaunchedEffect(dbManager.unlocked) {
        reload()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_manually))
            }
        }

        // 搜索框：按网站(url)或用户名(username)或标题(title)过滤
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text(stringResource(R.string.search_vault_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )

        val query = searchQuery.text.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            entries
        } else {
            entries.filter { e ->
                e.url.lowercase().contains(query) ||
                        e.username.lowercase().contains(query) ||
                        e.title.lowercase().contains(query)
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.vault_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.id.toString() }) { entry ->
                    PasswordBookCard(
                        entry = entry,
                        dbManager = dbManager,
                        onCopy = { onCopy(String(entry.password)) },
                        onClick = { onViewHistory(entry) },
                        onDelete = {
                            dbManager.deleteEntry(entry)
                            dbManager.saveDatabase()
                            reload()
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
                reload()
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun PasswordBookCard(
    entry: EntryKDBX,
    dbManager: DatabaseManager,
    onCopy: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val masterPassword = dbManager.getMasterPasswordFromEntry(entry)
    val version = dbManager.getVersionFromEntry(entry)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
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
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_desc)) }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_desc)) }
                }
            }
            Text(
                text = String(entry.password),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
            // 版本号（第几个版本 / LessPass 计数器数值）
            Text(
                text = stringResource(R.string.version_label, version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            masterPassword.takeIf { it.isNotEmpty() }?.let { mp ->
                var showMp by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.master_password_prefix, if (showMp) mp else "•".repeat(mp.length.coerceAtLeast(6))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showMp = !showMp }) {
                        Icon(
                            if (showMp) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showMp) stringResource(R.string.hide_master_password) else stringResource(R.string.show_master_password)
                        )
                    }
                }
            }
        }
    }
}
