package com.lesspass.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ShareCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lesspass.app.R
import com.lesspass.app.crypto.Finger
import com.lesspass.app.crypto.LessPassEngine
import com.lesspass.app.crypto.PasswordProfile
import com.lesspass.app.data.DatabaseManager
import com.lesspass.app.data.TimeoutManager
import com.lesspass.app.data.PasswordEntry
import com.lesspass.app.data.DatabaseManager.KdbxFileInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val dbManager = remember { DatabaseManager(context) }
                    // 无密码密码本自动解锁
                    val autoUnlock = remember { dbManager.autoUnlock }
                    var isUnlocked by remember(autoUnlock) { mutableStateOf(dbManager.unlocked || autoUnlock) }
                    val timeoutManager = remember { TimeoutManager(dbManager, onLock = { isUnlocked = false }) }

                    if (!isUnlocked) {
                        UnlockScreen(
                            dbManager = dbManager,
                            onUnlocked = { isUnlocked = true }
                        )
                    } else {
                        MainScreen(dbManager = dbManager, timeoutManager = timeoutManager)
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(dbManager: DatabaseManager, timeoutManager: TimeoutManager) {
    LaunchedEffect(Unit) {
        timeoutManager.start()
    }
    DisposableEffect(Unit) {
        onDispose { timeoutManager.stop() }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("生成") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("历史") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    label = { Text("密码本") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> GenerateScreen(dbManager = dbManager)
                1 -> HistoryScreen(dbManager = dbManager, onCopy = { copyToClipboard(context, it, "已复制到剪贴板") })
                2 -> PasswordBookScreen(dbManager = dbManager, onCopy = { copyToClipboard(context, it, "已复制到剪贴板") })
                3 -> SettingsScreen(dbManager = dbManager)
            }
        }
    }
}

/**
 * 生成密码页面设置持久化
 */
object GenerateSettings {
    private const val PREF_NAME = "generate_prefs"
    private const val KEY_MASTER_PASSWORD = "gen_master_password"
    private const val KEY_COUNTER = "gen_counter"
    private const val KEY_LENGTH = "gen_length"
    private const val KEY_LOWERCASE = "gen_lowercase"
    private const val KEY_UPPERCASE = "gen_uppercase"
    private const val KEY_DIGITS = "gen_digits"
    private const val KEY_SYMBOLS = "gen_symbols"
    private const val KEY_EXCLUDE_AMBIGUOUS = "gen_exclude_ambiguous"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GenerateScreenState {
        val p = prefs(context)
        return GenerateScreenState(
            masterPassword = p.getString(KEY_MASTER_PASSWORD, "") ?: "",
            counter = p.getInt(KEY_COUNTER, 1),
            length = p.getInt(KEY_LENGTH, 16),
            lowercase = p.getBoolean(KEY_LOWERCASE, true),
            uppercase = p.getBoolean(KEY_UPPERCASE, true),
            digits = p.getBoolean(KEY_DIGITS, true),
            symbols = p.getBoolean(KEY_SYMBOLS, true),
            excludeAmbiguous = p.getBoolean(KEY_EXCLUDE_AMBIGUOUS, false),
        )
    }

    fun save(context: Context, state: GenerateScreenState) {
        prefs(context).edit().apply {
            putString(KEY_MASTER_PASSWORD, state.masterPassword)
            putInt(KEY_COUNTER, state.counter)
            putInt(KEY_LENGTH, state.length)
            putBoolean(KEY_LOWERCASE, state.lowercase)
            putBoolean(KEY_UPPERCASE, state.uppercase)
            putBoolean(KEY_DIGITS, state.digits)
            putBoolean(KEY_SYMBOLS, state.symbols)
            putBoolean(KEY_EXCLUDE_AMBIGUOUS, state.excludeAmbiguous)
            apply()
        }
    }
}

data class GenerateScreenState(
    val masterPassword: String,
    val counter: Int,
    val length: Int,
    val lowercase: Boolean,
    val uppercase: Boolean,
    val digits: Boolean,
    val symbols: Boolean,
    val excludeAmbiguous: Boolean,
)

@Composable
fun GenerateScreen(dbManager: DatabaseManager) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 加载持久化设置
    val savedSettings = remember { GenerateSettings.load(context) }

    // 主密码：优先用保存的，其次用解锁密码，最后空
    val defaultMasterPassword = savedSettings.masterPassword

    var site by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var masterPassword by remember { mutableStateOf(defaultMasterPassword) }
    var counter by remember { mutableIntStateOf(savedSettings.counter) }
    var length by remember { mutableIntStateOf(savedSettings.length) }
    var lowercase by remember { mutableStateOf(savedSettings.lowercase) }
    var uppercase by remember { mutableStateOf(savedSettings.uppercase) }
    var digits by remember { mutableStateOf(savedSettings.digits) }
    var symbols by remember { mutableStateOf(savedSettings.symbols) }
    var excludeAmbiguous by remember { mutableStateOf(savedSettings.excludeAmbiguous) }
    var password by remember { mutableStateOf<String?>(null) }
    var seePassword by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<List<Finger>>(emptyList()) }
    var algorithmSupported by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // 初始化指纹
    LaunchedEffect(masterPassword) {
        if (masterPassword.isNotEmpty()) {
            fingerprint = LessPassEngine.buildFingerprint(masterPassword)
        } else {
            fingerprint = emptyList()
        }
    }

    val masterPasswordRequiredMsg = stringResource(R.string.master_password_required)
    val siteRequiredMsg = stringResource(R.string.site_required)

    LaunchedEffect(Unit) {
        val supported = LessPassEngine.isSupported()
        algorithmSupported = supported
        if (!supported) {
            Toast.makeText(context, "⚠️ 算法自检失败，密码可能与官方不兼容", Toast.LENGTH_LONG).show()
        }
    }

    // 持久化设置（每次变化时保存，不重置主密码/勾选/位数）
    LaunchedEffect(masterPassword, counter, length, lowercase, uppercase, digits, symbols, excludeAmbiguous) {
        GenerateSettings.save(context, GenerateScreenState(
            masterPassword = masterPassword,
            counter = counter,
            length = length,
            lowercase = lowercase,
            uppercase = uppercase,
            digits = digits,
            symbols = symbols,
            excludeAmbiguous = excludeAmbiguous,
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = site,
            onValueChange = { site = it },
            label = { Text(stringResource(R.string.site)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text(stringResource(R.string.login)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = masterPassword,
                onValueChange = { masterPassword = it },
                label = { Text(stringResource(R.string.master_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (masterPassword.isNotEmpty() && fingerprint.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fingerprint.forEach { finger ->
                        Icon(
                            imageVector = iconForFinger(finger.icon.name),
                            contentDescription = null,
                            tint = parseColor(finger.color.hex),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OptionItem(stringResource(R.string.lowercase), lowercase) { lowercase = it }
            OptionItem(stringResource(R.string.uppercase), uppercase) { uppercase = it }
            OptionItem(stringResource(R.string.digits), digits) { digits = it }
            OptionItem(stringResource(R.string.symbols), symbols) { symbols = it }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = excludeAmbiguous, onCheckedChange = { excludeAmbiguous = it })
                Text(
                    text = "排除近似字符 (0/O/o, 1/l/I/i, |, `)",
                    fontSize = 14.sp,
                )
            }
            Text(
                text = "⚠ 开启后密码与官方 LessPass 不兼容，仅本应用内跨设备一致",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CounterStepper(
                label = stringResource(R.string.length),
                value = length,
                onValueChange = { length = it.coerceIn(4, 64) },
                modifier = Modifier.weight(1f)
            )
            CounterStepper(
                label = stringResource(R.string.counter),
                value = counter,
                onValueChange = { counter = it.coerceAtLeast(1) },
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = {
                val profile = PasswordProfile(
                    site = site,
                    login = login,
                    counter = counter,
                    length = length,
                    uppercase = uppercase,
                    lowercase = lowercase,
                    digits = digits,
                    symbols = symbols,
                    excludeAmbiguous = excludeAmbiguous,
                )
                if (masterPassword.isEmpty()) {
                    Toast.makeText(context, masterPasswordRequiredMsg, Toast.LENGTH_SHORT).show()
                } else if (site.isEmpty()) {
                    Toast.makeText(context, siteRequiredMsg, Toast.LENGTH_SHORT).show()
                } else {
                    val pwd = LessPassEngine.generatePassword(profile, masterPassword)
                    password = pwd
                    seePassword = true
                    showSaveDialog = true

                    dbManager.addHistoryEntry(
                        site = site,
                        login = login,
                        password = pwd,
                        masterPassword = masterPassword,
                        length = length,
                    )
                    dbManager.saveDatabase()
                }
            },
            enabled = algorithmSupported,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (algorithmSupported) stringResource(R.string.generate) else "自检中…",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryActionButton(
                icon = Icons.Filled.Refresh,
                text = stringResource(R.string.clear),
                modifier = Modifier.weight(1f)
            ) {
                // 只重置 site 和 login，保留主密码/勾选/位数
                password = null
                seePassword = false
                fingerprint = emptyList()
                site = ""
                login = ""
                showSaveDialog = false
            }
            if (password != null) {
                SecondaryActionButton(
                    icon = Icons.Filled.ContentCopy,
                    text = stringResource(R.string.copy),
                    modifier = Modifier.weight(1f)
                ) {
                    copyToClipboard(context, password!!, "已复制到剪贴板")
                }
                SecondaryActionButton(
                    icon = if (seePassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    text = if (seePassword) stringResource(R.string.hide) else stringResource(R.string.show),
                    modifier = Modifier.weight(1f)
                ) {
                    seePassword = !seePassword
                }
            }
        }

        if (password != null) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (seePassword) password!! else "•".repeat(password!!.length),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }
    }

    if (showSaveDialog && password != null) {
        Button(
            onClick = {
                if (dbManager.unlocked) {
                    dbManager.addPasswordBookEntry(
                        title = site,
                        username = login,
                        password = password!!,
                        url = site,
                        notes = "count=$counter, length=$length, exclude=$excludeAmbiguous\n主密码: ${masterPassword}"
                    )
                    dbManager.saveDatabase()
                    Toast.makeText(context, "已保存到密码本", Toast.LENGTH_SHORT).show()
                    showSaveDialog = false
                } else {
                    Toast.makeText(context, "请先解锁密码本", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 4.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.Storage, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("保存到密码本")
        }
    }
}

@Composable
fun SettingsScreen(dbManager: DatabaseManager) {
    val context = LocalContext.current
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var moveError by remember { mutableStateOf<String?>(null) }

    // 密码本文件列表（与状态面板数据同步）
    var kdbxFileList by remember { mutableStateOf<List<KdbxFileInfo>>(emptyList()) }
    var showKdbxFilePasswordDialog by remember { mutableStateOf<KdbxFileInfo?>(null) }

    fun refreshFileList() {
        val folder = dbManager.currentKdbxFile?.parentFile ?: context.filesDir
        kdbxFileList = dbManager.listKdbxFiles(folder)
    }
    LaunchedEffect(dbManager.unlocked) { refreshFileList() }

    // 选择目标文件夹（使用系统文件夹选择器 OpenDocumentTree）
    val pickMoveFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) { moveError = null; return@rememberLauncherForActivityResult }
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (dbManager.moveDatabaseByUri(uri)) {
            Toast.makeText(context, "文件位置已修改", Toast.LENGTH_SHORT).show()
            moveError = null
            refreshFileList()
        } else {
            moveError = "修改失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // ==================== 密码本状态（整合文件列表） ====================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("密码本状态", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { refreshFileList() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("刷新", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 状态信息
                Text("文件路径: ${dbManager.filePath}", style = MaterialTheme.typography.bodySmall)
                Text("已加密: ${if (dbManager.hasPassword) "是" else "否"}", style = MaterialTheme.typography.bodySmall)
                Text("文件数量: ${kdbxFileList.size} 个", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))

                // 文件列表
                if (kdbxFileList.isEmpty()) {
                    Text("暂无密码本文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        kdbxFileList.forEach { fileInfo ->
                            KdbxFileEntry(
                                fileInfo = fileInfo,
                                isCurrent = fileInfo.path == dbManager.currentKdbxFile?.absolutePath,
                                onSelect = { showKdbxFilePasswordDialog = fileInfo },
                                onDelete = {
                                    val f = File(fileInfo.path)
                                    dbManager.deleteKdbxFile(f)
                                    refreshFileList()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 清除所有数据
                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清除所有数据")
                }
            }
        }

        // 修改密码
        OutlinedButton(
            onClick = { showChangePasswordDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (dbManager.hasPassword) "修改 KDBX 密码" else "设置 KDBX 密码")
        }

        // 修改文件位置（用文件夹选择器）
        OutlinedButton(
            onClick = { pickMoveFolderLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("修改文件保存位置")
        }
        moveError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        // 导出 KDBX 文件
        OutlinedButton(
            onClick = {
                try {
                    val db = dbManager.getDatabase() ?: throw IllegalStateException("数据库未解锁")
                    val baos = java.io.ByteArrayOutputStream()
                    dbManager.exportToOutputStream(baos)
                    val bytes = baos.toByteArray()
                    val tempFile = File.createTempFile("kdbx_export_", ".kdbx", context.cacheDir)
                    tempFile.writeBytes(bytes)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile
                        )
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享 KDBX 文件"))
                    exportError = null
                } catch (e: Exception) {
                    exportError = "导出失败：${e.message}"
                    e.printStackTrace()
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("导出 KDBX 文件")
        }
        exportError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        // 密码本文件密码输入对话框
        KdbxFilePasswordDialogWrapper(
            selectedFile = showKdbxFilePasswordDialog,
            dbManager = dbManager,
            context = context,
            onDismiss = { showKdbxFilePasswordDialog = null },
            onSuccess = {
                showKdbxFilePasswordDialog = null
                refreshFileList()
                Toast.makeText(context, "已切换", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            dbManager = dbManager,
            onDismiss = { showChangePasswordDialog = false }
        )
    }
}

/** 外部 KDBX 文件密码输入对话框 */
@Composable
private fun ExternalKdbxPasswordDialog(
    uri: Uri,
    dbManager: DatabaseManager,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入密码本密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码本密码（可留空）") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (dbManager.openExternalKdbx(uri, password)) {
                        onSuccess()
                    } else {
                        error = "密码错误，请重试"
                    }
                }
            ) { Text("确认") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
}

/** 格式化修改时间 */
private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** 密码本文件列表行 */
@Composable
private fun KdbxFileEntry(
    fileInfo: KdbxFileInfo,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件名 + 属性信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileInfo.name,
                    style = if (isCurrent) MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            else MaterialTheme.typography.bodyMedium,
                )
                // 文件大小与修改时间
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = formatFileSize(fileInfo.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = formatDate(fileInfo.modifiedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isCurrent) {
                Text("（当前）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onSelect, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text("选择", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

/** 密码本文件密码输入对话框（包装器，处理 null 安全） */
@Composable
private fun KdbxFilePasswordDialogWrapper(
    selectedFile: KdbxFileInfo?,
    dbManager: DatabaseManager,
    context: Context,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    if (selectedFile == null) return
    KdbxFilePasswordDialog(
        fileInfo = selectedFile,
        dbManager = dbManager,
        context = context,
        onDismiss = onDismiss,
        onSuccess = onSuccess
    )
}

/** 密码本文件密码输入对话框 */
@Composable
private fun KdbxFilePasswordDialog(
    fileInfo: KdbxFileInfo,
    dbManager: DatabaseManager,
    context: Context,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入密码本密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件：${fileInfo.name}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码本密码（可留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (dbManager.selectKdbxFile(fileInfo.uri, password)) {
                        onSuccess()
                    } else {
                        error = "密码错误，请重试"
                    }
                }
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    dbManager: DatabaseManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 KDBX 密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dbManager.hasPassword) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码（留空表示不加密）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (dbManager.hasPassword && oldPassword != dbManager.masterPasswordValue) {
                        errorMessage = "当前密码不正确"
                        return@TextButton
                    }
                    if (newPassword.isNotEmpty() && confirmPassword != newPassword) {
                        errorMessage = "两次输入的新密码不一致"
                        return@TextButton
                    }
                    if (dbManager.changePassword(oldPassword, newPassword)) {
                        Toast.makeText(context, "密码修改成功", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        errorMessage = "密码修改失败"
                    }
                },
                enabled = true
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}



@Composable
private fun OptionItem(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(text = text, fontSize = 16.sp)
    }
}

@Composable
private fun CounterStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onValueChange(value - 1) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "decrease", tint = MaterialTheme.colorScheme.onPrimary)
            }
            Box(
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Text(
                    text = value.toString(),
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp
                )
            }
            IconButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "increase", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun SecondaryActionButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text)
    }
}

private fun iconForFinger(name: String): ImageVector {
    return when (name) {
        "fa-hashtag" -> Icons.Filled.Tag
        "fa-heart" -> Icons.Filled.Favorite
        "fa-hotel" -> Icons.Filled.Hotel
        "fa-university" -> Icons.Filled.AccountBalance
        "fa-plug" -> Icons.Filled.Power
        "fa-ambulance" -> Icons.Filled.LocalHospital
        "fa-bus" -> Icons.Filled.DirectionsBus
        "fa-car" -> Icons.Filled.DirectionsCar
        "fa-plane" -> Icons.Filled.Flight
        "fa-rocket" -> Icons.Filled.Rocket
        "fa-ship" -> Icons.Filled.Sailing
        "fa-subway" -> Icons.Filled.DirectionsSubway
        "fa-truck" -> Icons.Filled.LocalShipping
        "fa-jpy" -> Icons.Filled.CurrencyYen
        "fa-eur" -> Icons.Filled.Euro
        "fa-btc" -> Icons.Filled.CurrencyBitcoin
        "fa-usd" -> Icons.Filled.AttachMoney
        "fa-gbp" -> Icons.Filled.Euro
        "fa-archive" -> Icons.Filled.Archive
        "fa-area-chart" -> Icons.Filled.BarChart
        "fa-bed" -> Icons.Filled.Bed
        "fa-beer" -> Icons.Filled.LocalBar
        "fa-bell" -> Icons.Filled.Notifications
        "fa-binoculars" -> Icons.Filled.Visibility
        "fa-birthday-cake" -> Icons.Filled.Cake
        "fa-bomb" -> Icons.Filled.LocalFireDepartment
        "fa-briefcase" -> Icons.Filled.BusinessCenter
        "fa-bug" -> Icons.Filled.BugReport
        "fa-camera" -> Icons.Filled.PhotoCamera
        "fa-cart-plus" -> Icons.Filled.AddShoppingCart
        "fa-certificate" -> Icons.Filled.WorkspacePremium
        "fa-coffee" -> Icons.Filled.Coffee
        "fa-cloud" -> Icons.Filled.Cloud
        "fa-comment" -> Icons.Filled.Comment
        "fa-cube" -> Icons.Filled.ViewInAr
        "fa-cutlery" -> Icons.Filled.Restaurant
        "fa-database" -> Icons.Filled.Storage
        "fa-diamond" -> Icons.Filled.Diamond
        "fa-exclamation-circle" -> Icons.Filled.ErrorOutline
        "fa-eye" -> Icons.Filled.Visibility
        "fa-flag" -> Icons.Filled.Flag
        "fa-flask" -> Icons.Filled.Science
        "fa-futbol-o" -> Icons.Filled.SportsSoccer
        "fa-gamepad" -> Icons.Filled.SportsEsports
        "fa-graduation-cap" -> Icons.Filled.School
        else -> Icons.Filled.Star
    }
}

private fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color.Black
    }
}

private fun copyToClipboard(context: Context, text: String, message: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("密码生成器", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
