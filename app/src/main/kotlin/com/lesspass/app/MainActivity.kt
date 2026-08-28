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
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.MutableState
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ShareCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
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
import com.lesspass.app.data.CredentialStore
import com.lesspass.app.data.DatabaseManager
import com.lesspass.app.data.TimeoutManager
import com.lesspass.app.data.PasswordEntry
import com.lesspass.app.data.DatabaseManager.KdbxFileInfo
import com.lesspass.app.BuildConfig
import com.lesspass.app.ui.theme.MimaTheme
import com.lesspass.app.ui.theme.PresetSeed
import com.lesspass.app.ui.theme.ThemeMode
import com.lesspass.app.ui.theme.ThemePrefs
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed

/**
 * 继承 FragmentActivity：biometric 1.1.0 的 BiometricPrompt 只接受 FragmentActivity 宿主，
 * 指纹设置/解锁依赖它（用 ComponentActivity 时 `context as? FragmentActivity` 恒为 null，
 * 会误报「请先解锁密码本」且指纹永不生效）。
 *
 * ⚠️ 代价：FragmentActivity.startActivityForResult 强制 requestCode 只能用低 16 位，
 * 而 Activity Result API 内部生成的 requestCode 从 0x00010000 起，会抛
 * IllegalArgumentException（Can only use lower 16 bits for requestCode）。
 * 因此 SAF 文件夹选择器不使用 rememberLauncherForActivityResult，
 * 改用 startActivityForResult + 低位 requestCode（见 REQ_PICK_FOLDER）。
 */
class MainActivity : FragmentActivity() {

    /** SAF 文件夹选择器请求码：必须 < 0x10000（FragmentActivity 只放行低 16 位）。 */
    private val REQ_PICK_FOLDER = 0x0001

    private var pendingFolderCallback: ((Uri?) -> Unit)? = null

    /**
     * 启动 SAF 文件夹选择器，结果通过 [onResult] 回传。
     * 使用低位 requestCode 以符合 FragmentActivity 的 16 位限制。
     */
    fun pickFolder(onResult: (Uri?) -> Unit) {
        pendingFolderCallback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    @Deprecated("使用传统 onActivityResult 以规避 FragmentActivity 的 requestCode 16 位限制")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FOLDER) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            pendingFolderCallback?.invoke(uri)
            pendingFolderCallback = null
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val dbManager = remember {
                DatabaseManager(context).apply {
                    Log.d("MimaDB", "onCreate: hasDatabase=${hasDatabase}, autoUnlock=$autoUnlock, unlocked=$unlocked")
                    // 修复可能存在的错误 URI 存储（历史遗留问题）
                    fixInvalidDbUriIfNeeded()
                }
            }
            // isUnlocked 提升到 setContent 顶层作用域，使清除数据对话框的回调也能直接重置它，
            // 避免 recreate() 因 remember 缓存保留旧实例/旧状态而跳过解锁界面。
            var isUnlocked by remember { mutableStateOf(dbManager.unlocked) }
            // 主题偏好（Material You）：状态提升到顶层，设置页改动即时生效并持久化
            var themeState by remember { mutableStateOf(ThemePrefs.load(context)) }
            val onThemeChange: (ThemePrefs.State) -> Unit = { new ->
                themeState = new
                ThemePrefs.save(context, new)
            }
            MimaTheme(
                mode = themeState.mode,
                dynamicColor = themeState.dynamicColor,
                seed = themeState.seed,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 自动解锁（用户设置项，默认关闭）：
                    // - 无密码密码本：原本就免密，进入即加载（不影响无密码逻辑）。
                    // - 有密码密码本且开启自动解锁：用 CredentialStore 保存的密码自动打开。

                    LaunchedEffect(Unit) {
                        if (!dbManager.unlocked) {
                            // 无密码密码本：进入即自动解锁，无需用户点击（也不依赖 autoUnlock 开关）。
                            if (!dbManager.hasPassword) {
                                dbManager.openDatabase("")
                            } else if (dbManager.autoUnlock) {
                                // 有密码且开启过“自动解锁”：用保存的密码自动打开；
                                // 清除数据会移除 auto_unlock 标记，从而停在解锁界面手动输入。
                                val cred = CredentialStore(context)
                                val savedPwd = cred.getAutoPassword(dbManager.vaultId)
                                if (savedPwd != null) {
                                    dbManager.openDatabase(savedPwd)
                                }
                            }
                        }
                        isUnlocked = dbManager.unlocked
                    }
                    Log.d("MimaDB", "onCreate state: isUnlocked=$isUnlocked hasDatabase=${dbManager.hasDatabase}")
                    val timeoutManager = remember { TimeoutManager(dbManager, onLock = { isUnlocked = false }) }
                    val credentialStore = remember { CredentialStore(context) }

                    if (!isUnlocked) {
                        UnlockScreen(
                            dbManager = dbManager,
                            credentialStore = credentialStore,
                            onUnlocked = { isUnlocked = true }
                        )
                    } else {
                        MainScreen(
                            dbManager = dbManager,
                            timeoutManager = timeoutManager,
                            credentialStore = credentialStore,
                            onDataCleared = { isUnlocked = false },
                            themeState = themeState,
                            onThemeChange = onThemeChange,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    dbManager: DatabaseManager,
    timeoutManager: TimeoutManager,
    credentialStore: CredentialStore,
    onDataCleared: () -> Unit = {},
    themeState: ThemePrefs.State = ThemePrefs.State(),
    onThemeChange: (ThemePrefs.State) -> Unit = {},
) {
    LaunchedEffect(Unit) {
        // 自动解锁开启，或密码本未设置密码时，超时锁定功能禁用；否则按设置项启用
        if (dbManager.timeoutEnabled && !dbManager.autoUnlock && dbManager.hasPassword) {
            timeoutManager.setTimeout(dbManager.timeoutMinutes * 60 * 1000L)
            timeoutManager.start()
        } else {
            timeoutManager.stop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { timeoutManager.stop() }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 保存至密码本时的冲突 / 历史查看 对话框状态
    var conflictState by remember { mutableStateOf<ConflictState?>(null) }
    var historyEntry by remember { mutableStateOf<EntryKDBX?>(null) }
    val regenerateState = remember { mutableStateOf<RegenerateRequest?>(null) }

    fun saveToVault(
        site: String,
        username: String,
        password: String,
        masterPassword: String,
        version: Int,
    ) {
        scope.launch(Dispatchers.IO) {
            val existing = dbManager.findVaultEntry(site, username)
            if (existing == null) {
                dbManager.addPasswordBookEntry(
                    title = site,
                    username = username,
                    password = password,
                    url = site,
                    masterPassword = masterPassword,
                    version = version
                )
                dbManager.saveDatabase()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.saved_to_vault), Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    conflictState = ConflictState(
                        site = site,
                        username = username,
                        password = password,
                        masterPassword = masterPassword,
                        version = version,
                        existing = existing,
                        samePassword = String(existing.password) == password
                    )
                }
            }
        }
    }

    // 无应用级顶栏：各页面直接以内容标题开头（生成页的「生成密码」大标题即首元素），
    // 视觉更干净，避免与底部四标签栏形成双层导航。
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_generate)) },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_history)) },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_vault)) },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> GenerateScreen(
                    dbManager = dbManager,
                    onSave = { s, u, p, mp, v -> saveToVault(s, u, p, mp, v) },
                    pendingRegenerate = regenerateState
                )
                // 注意：onSave 为函数类型，使用位置参数 { s, u, p, mp, v -> ... }
                // 上方写法 { s, u, p, mp, v -> } 已正确，请勿改为命名参数
                1 -> HistoryScreen(
                    dbManager = dbManager,
                    onCopy = { copyToClipboard(context, it) },
                    onSave = { entry ->
                        // 从历史记录保存到密码本：保存后该记录仍保留在历史中
                        saveToVault(
                            site = entry.url,
                            username = entry.username,
                            password = String(entry.password),
                            masterPassword = dbManager.getMasterPasswordFromEntry(entry),
                            version = dbManager.getVersionFromEntry(entry)
                        )
                    }
                )
                2 -> PasswordBookScreen(
                    dbManager = dbManager,
                    onCopy = { copyToClipboard(context, it) },
                    onViewHistory = { historyEntry = it }
                )
                3 -> SettingsScreen(
                    dbManager = dbManager,
                    credentialStore = credentialStore,
                    onDataCleared = onDataCleared,
                    themeState = themeState,
                    onThemeChange = onThemeChange,
                )
            }
        }
    }

    // 冲突提示对话框
    conflictState?.let { cs ->
        ConflictDialog(
            state = cs,
            onViewExisting = {
                copyToClipboard(context, String(cs.existing.password))
                conflictState = null
            },
            onOverwrite = {
                scope.launch(Dispatchers.IO) {
                    dbManager.overwriteVaultEntry(
                        entry = cs.existing,
                        password = cs.password,
                        masterPassword = cs.masterPassword,
                        version = cs.version
                    )
                    withContext(Dispatchers.Main) {
                        conflictState = null
                        Toast.makeText(context, context.getString(R.string.saved_to_vault), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRegenerate = {
                // 计数器+1，切到生成页，保留网站/用户名输入不清除
                regenerateState.value = RegenerateRequest(cs.site, cs.username, cs.version + 1)
                selectedTab = 0
                conflictState = null
            },
            onDismiss = { conflictState = null }
        )
    }

    // 查看密码本条目的自身历史版本
    historyEntry?.let { entry ->
        EntryHistoryDialog(
            dbManager = dbManager,
            entry = entry,
            onCopy = { copyToClipboard(context, it) },
            onDismiss = { historyEntry = null }
        )
    }
}

/**
 * 生成密码页面设置持久化
 */
object GenerateSettings {
    private const val PREF_NAME = "generate_prefs"
    private const val KEY_MASTER_PASSWORD = "gen_master_password"
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
            counter = 1,
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

/**
 * 评估主密码强度。基于长度与字符种类多样性给出一个 0..1 的分数，并映射为
 * 弱/中/强/很强四档标签与对应颜色。
 */
private data class PasswordStrength(val score: Float, val level: Int, val color: androidx.compose.ui.graphics.Color)

private fun evaluatePasswordStrength(pwd: String): PasswordStrength {
    if (pwd.isEmpty()) return PasswordStrength(0f, 0, androidx.compose.ui.graphics.Color.Gray)
    var types = 0
    if (pwd.any { it.isLowerCase() }) types++
    if (pwd.any { it.isUpperCase() }) types++
    if (pwd.any { it.isDigit() }) types++
    if (pwd.any { !it.isLetterOrDigit() }) types++
    // 长度得分（12 位封顶）+ 种类得分
    val lengthScore = (pwd.length.coerceAtMost(16) / 16f) * 0.6f
    val typeScore = (types / 4f) * 0.4f
    val score = (lengthScore + typeScore).coerceIn(0f, 1f)
    val level = when {
        score < 0.35f -> 1
        score < 0.6f -> 2
        score < 0.85f -> 3
        else -> 4
    }
    val color = when (level) {
        1 -> androidx.compose.ui.graphics.Color(0xFFE53935.toInt())
        2 -> androidx.compose.ui.graphics.Color(0xFFFB8C00.toInt())
        3 -> androidx.compose.ui.graphics.Color(0xFF43A047.toInt())
        else -> androidx.compose.ui.graphics.Color(0xFF1E88E5.toInt())
    }
    return PasswordStrength(score, level, color)
}

private fun strengthLabelRes(level: Int): Int = when (level) {
    0 -> R.string.password_strength_none
    1 -> R.string.password_strength_weak
    2 -> R.string.password_strength_medium
    3 -> R.string.password_strength_strong
    else -> R.string.password_strength_very_strong
}

@Composable
fun GenerateScreen(
    dbManager: DatabaseManager,
    onSave: (site: String, username: String, password: String, masterPassword: String, version: Int) -> Unit,
    pendingRegenerate: MutableState<RegenerateRequest?>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 加载持久化设置
    val savedSettings = remember { GenerateSettings.load(context) }

    // 主密码：优先用保存的，其次用解锁密码，最后空
    val defaultMasterPassword = savedSettings.masterPassword

    var site by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var masterPassword by remember { mutableStateOf(defaultMasterPassword) }
    var counter by remember { mutableIntStateOf(1) }
    var length by remember { mutableIntStateOf(savedSettings.length) }
    var lowercase by remember { mutableStateOf(savedSettings.lowercase) }
    var uppercase by remember { mutableStateOf(savedSettings.uppercase) }
    var digits by remember { mutableStateOf(savedSettings.digits) }
    var symbols by remember { mutableStateOf(savedSettings.symbols) }
    var excludeAmbiguous by remember { mutableStateOf(savedSettings.excludeAmbiguous) }
    var password by remember { mutableStateOf<String?>(null) }
    var showMaster by remember { mutableStateOf(false) }
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

    // 冲突后"重新生成"信号：保留网站/用户名，计数器+1，不清除输入
    LaunchedEffect(pendingRegenerate.value) {
        pendingRegenerate.value?.let { req ->
            site = req.site
            login = req.username
            counter = req.counter
            password = null
            fingerprint = emptyList()
            pendingRegenerate.value = null
        }
    }

    val masterPasswordRequiredMsg = stringResource(R.string.master_password_required)
    val siteRequiredMsg = stringResource(R.string.site_required)

    LaunchedEffect(Unit) {
        val supported = LessPassEngine.isSupported()
        algorithmSupported = supported
        if (!supported) {
            Toast.makeText(context, context.getString(R.string.self_test_failed), Toast.LENGTH_LONG).show()
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 设计稿 v4：屏幕顶部语义大标题 + 副标题说明
        Text(
            text = stringResource(R.string.generate_hero_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        )
        Text(
            text = stringResource(R.string.generate_hero_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
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
                visualTransformation = if (showMaster) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    if (masterPassword.isNotEmpty()) {
                        IconButton(onClick = { showMaster = !showMaster }) {
                            Icon(
                                imageVector = if (showMaster) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showMaster) stringResource(R.string.hide_master_password) else stringResource(R.string.show_master_password)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (masterPassword.isNotEmpty() && fingerprint.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 48.dp),
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

        // 主密码强度显示
        val strength = remember(masterPassword) { evaluatePasswordStrength(masterPassword) }
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { strength.score },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = strength.color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = if (masterPassword.isEmpty()) stringResource(R.string.password_strength_prefix) + stringResource(R.string.password_strength_none) else stringResource(R.string.password_strength_prefix) + stringResource(strengthLabelRes(strength.level)),
                fontSize = 12.sp,
                color = strength.color,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
        }

        // 设计稿 v4：字符集与长度/计数器归入同一张卡片（圆角 12dp + 卡片底色 + 分割线），
        // 取代原先平铺散落的控件，降低信息密度压力。
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.section_options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OptionItem(stringResource(R.string.lowercase), lowercase) { lowercase = it }
                    OptionItem(stringResource(R.string.uppercase), uppercase) { uppercase = it }
                    OptionItem(stringResource(R.string.digits), digits) { digits = it }
                    OptionItem(stringResource(R.string.symbols), symbols) { symbols = it }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = excludeAmbiguous, onCheckedChange = { excludeAmbiguous = it })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.exclude_ambiguous),
                            fontSize = 14.sp,
                        )
                        Text(
                            text = stringResource(R.string.exclude_ambiguous_warning),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
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
            }
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
                    showSaveDialog = true

                    scope.launch(Dispatchers.IO) {
                        dbManager.addHistoryEntry(
                            site = site,
                            login = login,
                            password = pwd,
                            masterPassword = masterPassword,
                        )
                        dbManager.saveDatabase()
                    }
                }
            },
            enabled = algorithmSupported,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (algorithmSupported) stringResource(R.string.generate) else stringResource(R.string.self_testing),
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
                fingerprint = emptyList()
                site = ""
                login = ""
                showSaveDialog = false
            }
            if (password != null) {
                SecondaryActionButton(
                    icon = Icons.Filled.Storage,
                    text = stringResource(R.string.save),
                    modifier = Modifier.weight(1f),
                    tonal = true
                ) {
                    if (dbManager.unlocked) {
                        onSave(site, login, password!!, masterPassword, counter)
                    } else {
                        Toast.makeText(context, context.getString(R.string.unlock_vault_first), Toast.LENGTH_SHORT).show()
                    }
                }
                SecondaryActionButton(
                    icon = Icons.Filled.ContentCopy,
                    text = stringResource(R.string.copy),
                    modifier = Modifier.weight(1f)
                ) {
                    copyToClipboard(context, password!!)
                }
            }
        }

        if (password != null) {
            // 设计稿 .pw-card：surface-variant 底色 + 圆角 12dp + 等宽字体，居中展示生成结果
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = password!!,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }

            // 生成密码的强度显示
            val pwdStrength = remember(password) { evaluatePasswordStrength(password!!) }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                LinearProgressIndicator(
                    progress = { pwdStrength.score },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = pwdStrength.color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = stringResource(R.string.password_strength_of) + stringResource(strengthLabelRes(pwdStrength.level)),
                    fontSize = 12.sp,
                    color = pwdStrength.color,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    dbManager: DatabaseManager,
    credentialStore: CredentialStore,
    onDataCleared: () -> Unit = {},
    themeState: ThemePrefs.State = ThemePrefs.State(),
    onThemeChange: (ThemePrefs.State) -> Unit = {},
) {
    val context = LocalContext.current
    var showChangePasswordDialog by remember { mutableStateOf(false) }

    // ============ 安全设置状态 ============
    var timeoutEnabled by remember { mutableStateOf(dbManager.timeoutEnabled) }
    var timeoutMinutes by remember { mutableStateOf(dbManager.timeoutMinutes) }
    var autoUnlock by remember { mutableStateOf(dbManager.autoUnlock) }
    val hasPassword = dbManager.hasPassword
    val scope = rememberCoroutineScope()
    // 密码本未设置密码时，超时锁定不可用（锁定后无法解锁）
    val timeoutUsable = hasPassword && !autoUnlock
    var fpEnabled by remember { mutableStateOf(credentialStore.hasFingerprintPassword(dbManager.vaultId)) }
    var fpSnackbar by remember { mutableStateOf<String?>(null) }
    val timeoutOptions = listOf(1, 5, 15, 30)
    var timeoutExpanded by remember { mutableStateOf(false) }
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

    // 目标文件夹选择器：
    // 不用 rememberLauncherForActivityResult —— 宿主是 FragmentActivity 时，
    // Activity Result API 生成的 requestCode 高 16 位非零会触发
    // IllegalArgumentException（Can only use lower 16 bits for requestCode）导致闪退。
    // 改用 Activity 的 pickFolder()：内部以低位 requestCode 启动选择器，
    // 结果经 onActivityResult 回传给 onResult 回调。
    fun launchFolderPicker() {
        (context as? MainActivity)?.pickFolder { uri ->
            if (uri != null) handleFolderSelected(uri)
        }
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
                migrationResultMessage = context.getString(R.string.renamed_migrated, newName)
            } else {
                migrationResultMessage = context.getString(R.string.migrated_success)
            }
            showMigrationResultDialog = true
            Toast.makeText(context, migrationResultMessage, Toast.LENGTH_LONG).show()
            refreshFileList()
        } else {
            moveError = errorMsg.ifBlank { context.getString(R.string.migrate_failed) }
        }
        showMigrateConfirmDialog = false
        pendingFolderUri = null
    }

    // 处理不迁移操作 - 创建新密码本
    // 文件名不再由用户输入，统一采用本应用约定名 mm_<机型>（createNewKdbxInFolder 的默认参数），
    // 既能保证在本应用文件列表中可识别（仅列出 mm_*.kdbx），又便于跨手机互传后识别。
    fun performCreateNew(password: String) {
        val uri = pendingFolderUri ?: return
        // 创建涉及重型 KDF 派生与 SAF 文件写入，必须在后台线程执行，否则会阻塞 UI 线程触发 ANR，
        // 甚至被系统在写入中途杀进程，留下 has_db=true 但文件损坏、重新进入后恒提示密码错误的状态。
        scope.launch(Dispatchers.IO) {
            val result = dbManager.createNewKdbxInFolder(uri, password = password)
            val success = result.first
            val errorMsg = result.third
            withContext(Dispatchers.Main) {
                if (success) {
                    migrationResultMessage = context.getString(R.string.new_vault_created)
                    showMigrationResultDialog = true
                    Toast.makeText(context, migrationResultMessage, Toast.LENGTH_SHORT).show()
                    refreshFileList()
                } else {
                    moveError = errorMsg.ifBlank { context.getString(R.string.create_failed) }
                }
                showCreateNewDialog = false
                pendingFolderUri = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== 外观（Material You 动态取色） ====================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 设计稿 .sec-title：区块小标题用弱化 labelMedium
                Text(
                    stringResource(R.string.appearance_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // 主题模式：跟随系统 / 亮色 / 暗色
                Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
                        ThemeMode.DARK to stringResource(R.string.theme_mode_dark),
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = themeState.mode == mode,
                            onClick = { onThemeChange(themeState.copy(mode = mode)) },
                            label = { Text(label) }
                        )
                    }
                }

                // 动态取色开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dynamic_color), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.dynamic_color_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = themeState.dynamicColor,
                        onCheckedChange = { on ->
                            onThemeChange(themeState.copy(dynamicColor = on))
                        }
                    )
                }

                // 预设种子色板（动态取色关闭时生效）
                if (!themeState.dynamicColor) {
                    Text(
                        stringResource(R.string.preset_palette),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PresetSeed.entries.forEach { preset ->
                            val selected = themeState.seed == preset
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(preset.argb))
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .clickable { onThemeChange(themeState.copy(seed = preset)) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = preset.label,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==================== 安全设置 ====================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.security_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // 超时锁定
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.timeout_lock_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (!hasPassword) {
                                stringResource(R.string.timeout_lock_no_password)
                            } else {
                                stringResource(R.string.timeout_lock_summary)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = timeoutUsable && timeoutEnabled,
                        enabled = timeoutUsable,
                        onCheckedChange = {
                            timeoutEnabled = it
                            dbManager.setTimeoutEnabled(it)
                        }
                    )
                }
                if (timeoutUsable && timeoutEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.lock_duration), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        ExposedDropdownMenuBox(
                            expanded = timeoutExpanded,
                            onExpandedChange = { timeoutExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = stringResource(R.string.minutes, timeoutMinutes),
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable, true).width(120.dp),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(
                                expanded = timeoutExpanded,
                                onDismissRequest = { timeoutExpanded = false }
                            ) {
                                timeoutOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.minutes, opt)) },
                                        onClick = {
                                            timeoutMinutes = opt
                                            dbManager.setTimeoutMinutes(opt)
                                            timeoutExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 自动解锁
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_unlock_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.auto_unlock_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoUnlock,
                        enabled = dbManager.hasPassword,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (dbManager.unlocked) {
                                    credentialStore.storeAutoPassword(dbManager.vaultId, dbManager.currentPassword ?: "")
                                    dbManager.setAutoUnlock(true)
                                    autoUnlock = true
                                } else {
                                    fpSnackbar = context.getString(R.string.unlock_first_auto_unlock)
                                }
                            } else {
                                credentialStore.clearAutoPassword(dbManager.vaultId)
                                dbManager.setAutoUnlock(false)
                                autoUnlock = false
                            }
                        }
                    )
                }
                if (autoUnlock) {
                    Text(
                        stringResource(R.string.auto_unlock_enabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 指纹解锁
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.fingerprint_unlock), style = MaterialTheme.typography.bodyMedium)
                        val vaultName = dbManager.currentKdbxFile?.name ?: dbManager.currentKdbxUri?.lastPathSegment ?: stringResource(R.string.current_vault_fallback)
                        Text(
                            "${vaultName}：${if (fpEnabled) stringResource(R.string.fingerprint_set) else stringResource(R.string.fingerprint_not_set)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (fpEnabled) {
                            Text(
                                stringResource(R.string.fingerprint_switch_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!fpEnabled) {
                        Button(
                            enabled = dbManager.unlocked && dbManager.hasPassword && CredentialStore.isBiometricAvailable(context),
                            onClick = {
                                val activity = context as? FragmentActivity
                                if (activity == null || !dbManager.unlocked) {
                                    fpSnackbar = context.getString(R.string.unlock_vault_first_fingerprint)
                                    return@Button
                                }
                                credentialStore.setupFingerprintPassword(
                                    vaultId = dbManager.vaultId,
                                    password = dbManager.currentPassword ?: "",
                                    activity = activity,
                                    onSuccess = {
                                        fpEnabled = true
                                        fpSnackbar = context.getString(R.string.fingerprint_enabled_toast)
                                    },
                                    onError = { fpSnackbar = it }
                                )
                            }
                        ) {
                            Text(stringResource(R.string.setup_fingerprint))
                        }
                    } else {
                        OutlinedButton(onClick = {
                            credentialStore.clearFingerprintPassword(dbManager.vaultId)
                            fpEnabled = false
                            fpSnackbar = context.getString(R.string.fingerprint_cleared_toast)
                        }) {
                            Text(stringResource(R.string.clear_fingerprint))
                        }
                    }
                }
            }
        }
        fpSnackbar?.let {
            LaunchedEffect(it) {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                fpSnackbar = null
            }
        }

        // ==================== 密码本状态（整合文件列表） ====================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.vault_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { refreshFileList() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_desc), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.refresh), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 状态信息
                Text(stringResource(R.string.file_path, displayPath), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.encrypted, if (dbManager.hasPassword) stringResource(R.string.yes) else stringResource(R.string.no)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.file_count, kdbxFileList.size), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodySmall)
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
                                stringResource(R.string.no_vault_files),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.default_vault_not_created, dbManager.defaultKdbxName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.create_vault_hint),
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
                    Text(stringResource(R.string.clear_all_data))
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
            Text(if (dbManager.hasPassword) stringResource(R.string.change_kdbx_password) else stringResource(R.string.set_kdbx_password))
        }

        // 修改文件位置（自定义文件夹选择器，规避 FragmentActivity 的 requestCode 16 位限制）
        OutlinedButton(
            onClick = {
                launchFolderPicker()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.modify_file_location))
        }
        moveError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        // 导出 KDBX 文件
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
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
                        withContext(Dispatchers.Main) {
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_kdbx)))
                            exportError = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            exportError = context.getString(R.string.export_failed, e.message ?: "")
                        }
                        e.printStackTrace()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_kdbx_dialog))
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
                Toast.makeText(context, context.getString(R.string.switched), Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            dbManager = dbManager,
            credentialStore = credentialStore,
            onPasswordChanged = { fpEnabled = false },
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
            title = { Text(stringResource(R.string.migrate_location_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.migrate_location_msg))
                    Text(stringResource(R.string.migrate_location_choose))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.migrate_current),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.create_new_at_location),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        performMigration()
                    }
                ) { Text(stringResource(R.string.migrate)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMigrateConfirmDialog = false
                        showCreateNewDialog = true
                    }
                ) { Text(stringResource(R.string.create_new_vault_action)) }
            }
        )
    }

    // 创建新密码本对话框
    if (showCreateNewDialog) {
        CreateNewDatabaseDialog(
            dbManager = dbManager,
            onDismiss = {
                showCreateNewDialog = false
                pendingFolderUri = null
            },
            onConfirm = { password ->
                performCreateNew(password)
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
            title = { Text(stringResource(R.string.operation_done)) },
            text = {
                Text(migrationResultMessage ?: stringResource(R.string.operation_done_default))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMigrationResultDialog = false
                        migrationResultMessage = null
                        refreshFileList()
                    }
                ) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    // 清除所有数据对话框（含自动重启，方便重新创建密码本）
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(stringResource(R.string.clear_all_data_title)) },
            text = {
                Text(stringResource(R.string.clear_all_data_msg))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataDialog = false
                        val ok = dbManager.clearAllData()
                        // 重置内存中的解锁状态与数据库引用，保证回到全新状态。
                        // 注意：不要使用 recreate()，因为 dbManager 由 remember 缓存，
                        // 跨 recreate 仍保留同一实例，且 LaunchedEffect(Unit) 不会重跑，
                        // 导致 Compose 层的 isUnlocked 状态停留在 true，从而跳过解锁界面。
                        dbManager.lock()
                        // 通过回调通知 MainActivity 重置 Compose 解锁状态为未解锁，
                        // 停留在「创建密码本」界面（不可直接 recreate，否则 remember
                        // 缓存的旧实例/旧状态会导致跳过解锁界面）。
                        onDataCleared()
                        Toast.makeText(
                            context,
                            if (ok) context.getString(R.string.clear_done) else context.getString(R.string.clear_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) { Text(stringResource(R.string.clear_all_data), color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** 创建新密码本对话框（文件名由系统按约定自动生成，不向用户暴露） */
@Composable
private fun CreateNewDatabaseDialog(
    dbManager: DatabaseManager,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_vault_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.create_vault_hint2), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.vault_name_convention_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_optional)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.confirm_password_label)) },
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
                    if (password != confirmPassword) {
                        errorMessage = context.getString(R.string.passwords_mismatch)
                        return@TextButton
                    }
                    onConfirm(password)
                }
            ) { Text(stringResource(R.string.create_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
        title = { Text(stringResource(R.string.input_vault_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.vault_password_optional_label)) },
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
                        error = context.getString(R.string.wrong_password)
                    }
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
                Text(stringResource(R.string.current_marker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onSelect, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text(stringResource(R.string.select_action), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text(stringResource(R.string.delete_action), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
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
        title = { Text(stringResource(R.string.input_vault_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.file_colon, fileInfo.name), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.vault_password_optional_label)) },
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
                        error = context.getString(R.string.wrong_password)
                    }
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    dbManager: DatabaseManager,
    credentialStore: CredentialStore,
    onPasswordChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dbManager.hasPassword) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text(stringResource(R.string.current_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(stringResource(R.string.new_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.confirm_new_password_label)) },
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
                        errorMessage = context.getString(R.string.old_password_incorrect)
                        return@TextButton
                    }
                    if (newPassword.isNotEmpty() && confirmPassword != newPassword) {
                        errorMessage = context.getString(R.string.new_passwords_mismatch)
                        return@TextButton
                    }
                    scope.launch(Dispatchers.IO) {
                        val success = dbManager.changePassword(oldPassword, newPassword)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                // 密码已变更，旧的指纹凭据对应的密码失效，必须清除并提示重新设置
                                val fpVault = dbManager.vaultId
                                if (credentialStore.hasFingerprintPassword(fpVault)) {
                                    credentialStore.clearFingerprintPassword(fpVault)
                                    onPasswordChanged()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.password_changed_fingerprint_invalid),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.password_changed_success), Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            } else {
                                errorMessage = context.getString(R.string.password_change_failed)
                            }
                        }
                    }
                },
                enabled = true
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease_desc), tint = MaterialTheme.colorScheme.onPrimary)
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
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase_desc), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * 次级操作按钮。
 * @param tonal 为 true 时使用设计稿 .btn.tonal 样式（secondary-container 填充），
 *              用于「保存到密码本」等强调但仍次于主按钮的操作。
 */
@Composable
private fun SecondaryActionButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    onClick: () -> Unit,
) {
    val label: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text)
    }
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            content = { label() }
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            content = { label() }
        )
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

private fun copyToClipboard(context: Context, text: String, message: String? = null) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label), text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, message ?: context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

/** 冲突状态：保存至密码本时，发现已存在相同网站+用户名的条目 */
private data class ConflictState(
    val site: String,
    val username: String,
    val password: String,
    val masterPassword: String,
    val version: Int,
    val existing: EntryKDBX,
    val samePassword: Boolean,
)

/** 生成页"重新生成"请求：保留网站/用户名，计数器 +1 */
data class RegenerateRequest(
    val site: String,
    val username: String,
    val counter: Int,
)

@Composable
private fun ConflictDialog(
    state: ConflictState,
    onViewExisting: () -> Unit,
    onOverwrite: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Text(
                if (state.samePassword) stringResource(R.string.conflict_same_password)
                else stringResource(R.string.conflict_diff_password)
            )
        },
        confirmButton = {
            if (state.samePassword) {
                // 密码也相同：提示重新生成计数器+1 版本
                TextButton(onClick = onRegenerate) { Text(stringResource(R.string.regenerate_next)) }
            } else {
                // 密码不同：查看现有密码 / 覆盖保存
                TextButton(onClick = onViewExisting) { Text(stringResource(R.string.view_existing)) }
                TextButton(onClick = onOverwrite) { Text(stringResource(R.string.overwrite)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EntryHistoryDialog(
    dbManager: DatabaseManager,
    entry: EntryKDBX,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val history = remember(entry) { dbManager.getEntryHistory(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_title, entry.title.ifEmpty { entry.url })) },
        text = {
            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.history_empty_site),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(history, key = { index, h -> "${h.id}_${h.creationTime.toMilliseconds()}_$index" }) { _, h ->
                        val version = dbManager.getVersionFromEntry(h)
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.version_label, version),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = { onCopy(String(h.password)) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = stringResource(R.string.copy_desc),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    String(h.password),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    formatTime(h.creationTime.toMilliseconds()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}
