package com.lesspass.app


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<EntryKDBX>()) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun reload() {
        entries = dbManager.getPasswordBookEntries()
    }

    LaunchedEffect(dbManager.unlocked) {
        reload()
    }

    // 用 Box 而非 Scaffold：本页已嵌在 MainScreen 的 Scaffold 内，
    // 再套一层 Scaffold 会重复应用系统栏 inset，导致顶部出现多余空白（与其他页面高度不一致）。
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 设计稿 v4：搜索框为 surface-variant 胶囊（全圆角）
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
            ),
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
            // 设计稿 .empty：大图标 + 引导文案
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.vault_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id.toString() }) { entry ->
                    PasswordBookCard(
                        entry = entry,
                        dbManager = dbManager,
                        onCopy = { onCopy(String(entry.password)) },
                        onClick = { onViewHistory(entry) },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                dbManager.deleteEntry(entry)
                                dbManager.saveDatabase()
                                withContext(Dispatchers.Main) {
                                    reload()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

        // 设计稿 .fab：右下角悬浮 ＋ 承担「创建新条目」入口（Box 内绝对定位，不引入额外 inset）
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_manually))
        }
    }

    if (showAddDialog) {
        AddEntryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, username, password, url, notes ->
                scope.launch(Dispatchers.IO) {
                    dbManager.addPasswordBookEntry(
                        title = title,
                        username = username,
                        password = password,
                        url = url,
                        notes = notes
                    )
                    dbManager.saveDatabase()
                    withContext(Dispatchers.Main) {
                        reload()
                        showAddDialog = false
                    }
                }
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
