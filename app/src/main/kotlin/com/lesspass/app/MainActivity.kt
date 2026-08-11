package com.lesspass.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.lesspass.app.BuildConfig

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
                    val dbManager = remember {
                        DatabaseManager(context).apply {
                            Log.d("MimaDB", "onCreate: hasDatabase=${hasDatabase}, autoUnlock=$autoUnlock, unlocked=$unlocked")
                            // 修复可能存在的错误 URI 存储（历史遗留问题）
                            fixInvalidDbUriIfNeeded()
                        }
                    }
                    // 无密码密码本自动解锁
                    val autoUnlock = remember { dbManager.autoUnlock }
                    var isUnlocked by remember(autoUnlock) { mutableStateOf(dbManager.unlocked || autoUnlock) }
                    Log.d("MimaDB", "onCreate state: isUnlocked=$isUnlocked autoUnlock=$autoUnlock hasDatabase=${dbManager.hasDatabase}")
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
    // 当前保存位置的可读路径（可观察状态，确保修改保存位置后界面立即刷新）
    var displayPath by remember { mutableStateOf(dbManager.filePath) }
    var showKdbxFilePasswordDialog by remember { mutableStateOf<KdbxFileInfo?>(null) }

    // 迁移相关状态
    var pendingFolderUri by remember { mutableStateOf<Uri?>(null) }
    var showMigrateConfirmDialog by remember { mutableStateOf(false) }
    var showCreateNewDialog by remember { mutableStateOf(false) }
    var migrationResultMessage by remember { mutableStateOf<String?>(null) }
    var showMigrationResultDialog by remember { mutableStateOf(false) }

    fun refreshFileList() {
        kdbxFileList = dbManager.listKdbxFiles()
        displayPath = dbManager.filePath
    }
    LaunchedEffect(dbManager.unlocked) { refreshFileList() }

    // 处理修改保存位置的核心逻辑
    fun handleFolderSelected(uri: Uri) {
        // 持久化 URI 权限，确保后续（含 Activity 重建后）仍能读取/写入该目录
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w("MimaDB", "handleFolderSelected: takePersistableUriPermission failed", e)
        }
        // 保存待处理的文件夹 URI，弹出确认对话框
        pendingFolderUri = uri
        showMigrateConfirmDialog = true
    }

    // 处理迁移操作
    fun performMigration() {
        val uri = pendingFolderUri ?: return
        val result = dbManager.migrateCurrentFileToFolder(uri)
        val success = result.first
        val newName = result.second
        val errorMsg = result.third
        if (success) {
            if (newName.contains("(")) {
                migrationResultMessage = "检测到新目录中已存在同名文件，已自动将迁移的密码本重命名为 $newName"
            } else {
                migrationResultMessage = "密码本已成功迁移到新位置"
            }
            showMigrationResultDialog = true
            Toast.makeText(context, migrationResultMessage, Toast.LENGTH_LONG).show()
            refreshFileList()
        } else {
            moveError = errorMsg.ifBlank { "迁移失败，请重试" }
        }
        showMigrateConfirmDialog = false
        pendingFolderUri = null
    }

    // 处理不迁移操作 - 创建新密码本
    fun performCreateNew(fileName: String, password: String) {
        val uri = pendingFolderUri ?: return
        val result = dbManager.createNewKdbxInFolder(uri, fileName, password)
        val success = result.first
        val errorMsg = result.third
        if (success) {
            migrationResultMessage = "新密码本已创建成功"
            showMigrationResultDialog = true
            Toast.makeText(context, "新密码本创建成功", Toast.LENGTH_SHORT).show()
            refreshFileList()
        } else {
            moveError = errorMsg.ifBlank { "创建失败，请重试" }
        }
        showCreateNewDialog = false
        pendingFolderUri = null
    }

    // 选择目标文件夹（使用系统文件夹选择器 OpenDocumentTree）
    // 通过 StartActivityForResult 手动构造 Intent，并加上 FLAG_GRANT_PERSISTABLE_URI_PERMISSION，
    // 否则系统不会授予持久 URI 权限，导致迁移/创建后无法读取该目录下的密码本列表。
    val pickMoveFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) { moveError = null; return@rememberLauncherForActivityResult }
        val uri = result.data?.data
        if (uri == null) { moveError = null; return@rememberLauncherForActivityResult }
        moveError = null
        handleFolderSelected(uri)
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
                Text("文件路径: $displayPath", style = MaterialTheme.typography.bodySmall)
                Text("已加密: ${if (dbManager.hasPassword) "是" else "否"}", style = MaterialTheme.typography.bodySmall)
                Text("文件数量: ${kdbxFileList.size} 个", style = MaterialTheme.typography.bodySmall)
                Text("当前版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))

                // 文件列表
                Log.d("MimaDB", "SettingsScreen render: kdbxFileList.size=${kdbxFileList.size}, displayPath=$displayPath")
                if (kdbxFileList.isEmpty()) {
                    // 列表为空时，若默认密码本在逻辑上存在（如已通过 SAF 选取但未列出），
                    // 仍展示该默认项并标记为当前选中，避免界面显示为空白/无选中。
                    val defaultFile = dbManager.effectiveSelectedFile
                    if (defaultFile != null) {
                        val defaultInfo = KdbxFileInfo(
                            name = defaultFile.name,
                            path = defaultFile.absolutePath,
                            size = if (defaultFile.exists()) defaultFile.length() else 0,
                            modifiedAt = if (defaultFile.exists()) defaultFile.lastModified() else 0,
                            uri = dbManager.currentKdbxUri ?: android.net.Uri.fromFile(defaultFile),
                            hasPassword = false,
                            isFromSaf = dbManager.currentKdbxUri != null
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            KdbxFileEntry(
                                fileInfo = defaultInfo,
                                isCurrent = true,
                                onSelect = { showKdbxFilePasswordDialog = defaultInfo },
                                onDelete = {
                                    dbManager.currentKdbxUri?.let { dbManager.deleteKdbxFileByUri(it) }
                                        ?: dbManager.deleteKdbxFile(defaultFile)
                                    refreshFileList()
                                }
                            )
                        }
                    } else {
                        // 真正的空状态：友好提示用户如何创建默认密码本
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "暂无密码本文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "默认密码本：${dbManager.defaultKdbxName}（尚未创建）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "前往「生成」页生成并保存一个密码，或返回登录页创建密码本即可自动生成该文件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 是否已有显式选中的文件；没有时回退到默认密码本文件名高亮
                    val hasExplicit = dbManager.hasExplicitKdbxSelection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        kdbxFileList.forEach { fileInfo ->
                            KdbxFileEntry(
                                fileInfo = fileInfo,
                                isCurrent = fileInfo.uri == dbManager.currentKdbxUri ||
                                    fileInfo.path == dbManager.currentKdbxFile?.absolutePath ||
                                    (!hasExplicit && fileInfo.name == dbManager.defaultKdbxName),
                                onSelect = { showKdbxFilePasswordDialog = fileInfo },
                                onDelete = {
                                    android.util.Log.d("MimaDB", "onDelete clicked: name=${fileInfo.name}, isFromSaf=${fileInfo.isFromSaf}, uri=${fileInfo.uri}")
                                    if (fileInfo.isFromSaf) {
                                        val success = dbManager.deleteKdbxFileByUri(fileInfo.uri)
                                        android.util.Log.d("MimaDB", "onDelete: deleteSaf result=$success")
                                    } else {
                                        val f = File(fileInfo.path)
                                        android.util.Log.d("MimaDB", "onDelete: deleteLocal path=${f.absolutePath}, exists=${f.exists()}")
                                        dbManager.deleteKdbxFile(f)
                                    }
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
            onClick = {
                val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                pickMoveFolderLauncher.launch(treeIntent)
            },
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

    // 迁移确认对话框
    if (showMigrateConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showMigrateConfirmDialog = false
                pendingFolderUri = null
            },
            title = { Text("修改保存位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("您已选择了新的保存位置。")
                    Text("请选择如何处理当前的密码本：")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 迁移当前密码本到新位置",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "• 在新位置创建一个新的空密码本",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 检查是否有当前选中的文件
                        if (dbManager.currentKdbxUri != null || dbManager.currentKdbxFile != null) {
                            performMigration()
                        } else {
                            // 没有当前文件，直接迁移数据库
                            performMigration()
                        }
                    }
                ) { Text("迁移") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMigrateConfirmDialog = false
                        showCreateNewDialog = true
                    }
                ) { Text("创建新密码本") }
            }
        )
    }

    // 创建新密码本对话框
    if (showCreateNewDialog) {
        CreateNewDatabaseDialog(
            onDismiss = {
                showCreateNewDialog = false
                pendingFolderUri = null
            },
            onConfirm = { fileName, password ->
                performCreateNew(fileName, password)
            }
        )
    }

    // 迁移结果对话框
    if (showMigrationResultDialog) {
        AlertDialog(
            onDismissRequest = {
                showMigrationResultDialog = false
                migrationResultMessage = null
            },
            title = { Text("操作完成") },
            text = {
                Text(migrationResultMessage ?: "操作已完成")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMigrationResultDialog = false
                        migrationResultMessage = null
                        refreshFileList()
                    }
                ) { Text("确定") }
            }
        )
    }

    // 清除所有数据对话框（含自动重启，方便重新创建密码本）
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("清除所有数据") },
            text = {
                Text(
                    "将清除主密码、密码本密码、文件保存位置等全部设置，并自动重启应用以便重新创建密码本。" +
                        "已生成的密码本文件不会被删除（如需删除请在文件管理器中手动操作）。是否继续？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataDialog = false
                        val ok = dbManager.clearAllData()
                        // 重置内存中的解锁状态与数据库引用，保证重启后是全新状态
                        dbManager.lock()
                        Toast.makeText(
                            context,
                            if (ok) "已清除，正在重启应用…" else "清除失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                        // 自动重启 Activity，使用户可重新创建密码本
                        (context as? android.app.Activity)?.recreate()
                    }
                ) { Text("清除并重启") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 创建新密码本对话框 */
@Composable
private fun CreateNewDatabaseDialog(
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, password: String) -> Unit
) {
    var fileName by remember { mutableStateOf("password_book") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新密码本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将在新位置创建一个空的密码本", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名（不含扩展名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可留空，表示无加密）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { 
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) 
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (fileName.isBlank()) {
                        errorMessage = "文件名不能为空"
                        return@TextButton
                    }
                    if (password != confirmPassword) {
                        errorMessage = "两次输入的密码不一致"
                        return@TextButton
                    }
                    onConfirm(fileName, password)
                }
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
