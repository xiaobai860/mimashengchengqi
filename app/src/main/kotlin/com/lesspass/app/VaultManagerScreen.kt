package com.lesspass.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.lesspass.app.data.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 密码库管理页：管理/编辑整个 kdbx 密码文件（分组与条目），
 * 与「密码本」页（仅查看本应用保存的密码）区分开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultManagerScreen(
    dbManager: DatabaseManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf<List<GroupKDBX>>(emptyList()) }
    val currentGroup = stack.lastOrNull()

    var groups by remember { mutableStateOf(emptyList<GroupKDBX>()) }
    var entries by remember { mutableStateOf(emptyList<EntryKDBX>()) }

    var showAddMenu by remember { mutableStateOf(false) }
    var showAddGroup by remember { mutableStateOf(false) }
    var showAddEntry by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<EntryKDBX?>(null) }
    var renameGroup by remember { mutableStateOf<GroupKDBX?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupKDBX?>(null) }
    var deleteEntryTarget by remember { mutableStateOf<EntryKDBX?>(null) }

    fun reload() {
        groups = dbManager.getChildGroups(currentGroup)
        entries = dbManager.getChildEntries(currentGroup)
    }

    LaunchedEffect(stack, dbManager.unlocked) { reload() }

    fun runDb(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            block()
            withContext(Dispatchers.Main) { reload() }
        }
    }

    // 拦截系统返回键：分组栈逐级返回，栈空时回到密码本，而不是退出应用
    BackHandler {
        if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 行内标题栏：不使用自带状态栏 insets 的 TopAppBar，与一级页面内容起始位置对齐
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack() }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    if (stack.isEmpty()) stringResource(R.string.vault_manager_title)
                    else currentGroup?.title ?: "",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (groups.isEmpty() && entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vault_manager_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(groups, key = { it.id.toString() }) { group ->
                        VaultGroupRow(
                            group = group,
                            onClick = { stack = stack + group },
                            onRename = { renameGroup = group },
                            onDelete = { deleteGroupTarget = group },
                        )
                    }
                    items(entries, key = { it.id.toString() }) { entry ->
                        VaultEntryRow(
                            entry = entry,
                            onClick = { editEntry = entry },
                            onDelete = { deleteEntryTarget = entry },
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAddMenu = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
        }
    }

    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text(stringResource(R.string.add)) },
            text = {
                Column {
                    TextButton(onClick = { showAddMenu = false; showAddGroup = true }) {
                        Text(stringResource(R.string.add_group))
                    }
                    TextButton(onClick = { showAddMenu = false; showAddEntry = true }) {
                        Text(stringResource(R.string.add_entry))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMenu = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showAddGroup) {
        GroupNameDialog(
            title = stringResource(R.string.add_group_title),
            initial = "",
            onDismiss = { showAddGroup = false },
            onConfirm = { name ->
                showAddGroup = false
                runDb { dbManager.createGroup(currentGroup, name) }
            }
        )
    }

    renameGroup?.let { g ->
        GroupNameDialog(
            title = stringResource(R.string.rename_group),
            initial = g.title,
            onDismiss = { renameGroup = null },
            onConfirm = { name ->
                renameGroup = null
                runDb { dbManager.renameGroup(g, name) }
            }
        )
    }

    if (showAddEntry) {
        EntryEditorDialog(
            entry = null,
            onDismiss = { showAddEntry = false },
            onConfirm = { t, u, p, url, notes ->
                showAddEntry = false
                runDb { dbManager.createEntryInGroup(currentGroup, t, u, p, url, notes) }
            }
        )
    }

    editEntry?.let { e ->
        EntryEditorDialog(
            entry = e,
            onDismiss = { editEntry = null },
            onConfirm = { t, u, p, url, notes ->
                editEntry = null
                runDb { dbManager.updateEntryFields(e, t, u, p, url, notes) }
            }
        )
    }

    deleteGroupTarget?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text(stringResource(R.string.delete_group)) },
            text = { Text(stringResource(R.string.delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroupTarget = null
                    val removed = g
                    runDb { dbManager.deleteGroup(removed) }
                    stack = stack.filter { it != removed }
                }) { Text(stringResource(R.string.delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroupTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    deleteEntryTarget?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteEntryTarget = null },
            title = { Text(stringResource(R.string.delete_action)) },
            text = { Text(stringResource(R.string.delete_entry_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteEntryTarget = null
                    runDb { dbManager.deleteEntry(e) }
                }) { Text(stringResource(R.string.delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntryTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun VaultGroupRow(
    group: GroupKDBX,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rename_group)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_group)) }
        }
    }
}

@Composable
private fun VaultEntryRow(
    entry: EntryKDBX,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title.ifBlank { stringResource(R.string.no_title) }, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.username.ifBlank { stringResource(R.string.no_login) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_action)) }
        }
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EntryEditorDialog(
    entry: EntryKDBX?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, username: String, password: String, url: String, notes: String) -> Unit,
) {
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var username by remember { mutableStateOf(entry?.username ?: "") }
    var password by remember { mutableStateOf(entry?.let { String(it.password) } ?: "") }
    var url by remember { mutableStateOf(entry?.url ?: "") }
    var notes by remember { mutableStateOf(entry?.notes ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) stringResource(R.string.new_entry) else stringResource(R.string.edit_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.entry_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.vault_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.vault_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().autoMimaKeyboard(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    }
                )
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.vault_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.vault_notes)) },
                    singleLine = false, modifier = Modifier.fillMaxWidth().height(96.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() || username.isNotBlank() || password.isNotBlank(),
                onClick = { onConfirm(title.trim(), username, password, url.trim(), notes) }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
