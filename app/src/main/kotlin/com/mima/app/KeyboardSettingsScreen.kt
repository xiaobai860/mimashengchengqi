package com.mima.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mima.app.data.DatabaseManager
import com.mima.app.ui.theme.MimaShapes

/** 密码键盘设置页（从设置页「安全设置」区块进入）：
 *  启用键盘 → 自动填充 → 自动切入 → 自动切回 → 键盘乱序 → 防截屏说明。 */
@Composable
fun KeyboardSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var shuffle by remember { mutableStateOf(DatabaseManager.isKeyboardShuffleEnabled(context)) }
    // 自动切入模式（互斥）：ADB 直切 / 弹窗选择器；以及自动切回开关。
    // 自愈：持久化为 ADB 但 WRITE_SECURE_SETTINGS 已被撤销时，自动降级为关，避免开关卡在灰色选中态
    var adbSwitch by remember {
        val savedAdb = DatabaseManager.getSwitchInMode(context) == ImeAutoSwitch.MODE_ADB
        val valid = savedAdb && ImeAutoSwitch.hasWriteSecureSettings(context)
        if (savedAdb && !valid) DatabaseManager.setSwitchInMode(context, ImeAutoSwitch.MODE_OFF)
        mutableStateOf(valid)
    }
    var pickerSwitch by remember {
        mutableStateOf(DatabaseManager.getSwitchInMode(context) == ImeAutoSwitch.MODE_PICKER)
    }
    var switchOut by remember { mutableStateOf(DatabaseManager.isSwitchOutEnabled(context)) }
    var switchOutTarget by remember { mutableStateOf(DatabaseManager.getSwitchOutTarget(context)) }
    var graceSeconds by remember { mutableStateOf(DatabaseManager.getPickerGraceSeconds(context)) }
    // 系统已启用的输入法列表（排除自身与空标签），作为「切回目标」可选项
    val imeList = remember {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.filter { it.packageName != context.packageName }
            ?.map { it.id to it.loadLabel(context.packageManager).toString() }
            ?.filter { it.second.isNotBlank() }
            ?: emptyList()
    }
    // 以下三项依赖系统状态，从系统设置返回后（ON_RESUME）全部重新检测
    var autofillEnabled by remember { mutableStateOf(MimaAutofillService.isAutofillEnabled(context)) }
    var mimaImeReady by remember { mutableStateOf(ImeAutoSwitch.mimaImeId(context) != null) }
    var imePermissionGranted by remember { mutableStateOf(ImeAutoSwitch.hasWriteSecureSettings(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autofillEnabled = MimaAutofillService.isAutofillEnabled(context)
                mimaImeReady = ImeAutoSwitch.mimaImeId(context) != null
                imePermissionGranted = ImeAutoSwitch.hasWriteSecureSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val grantCmd = stringResource(R.string.keyboard_auto_switch_grant_cmd)

    // 拦截系统返回键：返回设置页而不是退出应用
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 行内标题栏：不使用自带状态栏 insets 的 TopAppBar，与一级页面内容起始位置对齐
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.keyboard_name), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 1. 启用密码键盘：所有功能的前提
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.keyboard_enable),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        stringResource(R.string.keyboard_enable_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MimaShapes.button
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.keyboard_enable_action))
                    }
                }
            }

            // 2. 自动填充：填充条目到其它应用 + 第三方密码框检测（按切入模式切换键盘）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.keyboard_autofill),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (autofillEnabled) R.string.keyboard_service_on
                                    else R.string.keyboard_service_off
                                ),
                                color = if (autofillEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.keyboard_autofill_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = try {
                                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                            } catch (_: Throwable) {
                                Intent(Settings.ACTION_SETTINGS)
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MimaShapes.button
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.keyboard_autofill_open))
                    }
                }
            }

            // 3. 键盘乱序
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.keyboard_shuffle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.keyboard_shuffle))
                            Text(
                                stringResource(R.string.keyboard_shuffle_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = shuffle,
                            onCheckedChange = {
                                shuffle = it
                                DatabaseManager.setKeyboardShuffleEnabled(context, it)
                            }
                        )
                    }
                }
            }

            // 4. 自动切入卡片：ADB 直切与弹窗选择器二选一（互斥），需键盘已在系统启用
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Autorenew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.keyboard_auto_switch),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 选项一：ADB 直切（仅授权后可开；开启时弹窗选项锁定关闭）
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.keyboard_switch_in_adb))
                            Text(
                                stringResource(R.string.keyboard_auto_switch_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = adbSwitch,
                            enabled = imePermissionGranted && mimaImeReady,
                            onCheckedChange = {
                                adbSwitch = it
                                if (it) pickerSwitch = false
                                DatabaseManager.setSwitchInMode(
                                    context,
                                    if (it) ImeAutoSwitch.MODE_ADB else ImeAutoSwitch.MODE_OFF
                                )
                            }
                        )
                    }
                    // 选项二：弹窗选择器（ADB 开启期间锁定禁用）
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.keyboard_switch_in_picker))
                            Text(
                                stringResource(R.string.keyboard_switch_in_picker_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = pickerSwitch,
                            enabled = !adbSwitch && mimaImeReady,
                            onCheckedChange = {
                                pickerSwitch = it
                                DatabaseManager.setSwitchInMode(
                                    context,
                                    if (it) ImeAutoSwitch.MODE_PICKER else ImeAutoSwitch.MODE_OFF
                                )
                            }
                        )
                    }
                    when {
                        !mimaImeReady -> Text(
                            stringResource(R.string.keyboard_auto_switch_need_enable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        !imePermissionGranted -> {
                            Text(
                                stringResource(R.string.keyboard_auto_switch_need_grant),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 点击复制 adb 命令
                            Text(
                                grantCmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("adb", grantCmd))
                                    Toast.makeText(context, R.string.keyboard_copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            // 4. 自动切回卡片：非密码框唤起键盘时由 IME 自主切回（无需授权）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.keyboard_switch_out),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.keyboard_switch_out))
                            Text(
                                stringResource(R.string.keyboard_switch_out_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = switchOut,
                            onCheckedChange = {
                                switchOut = it
                                DatabaseManager.setSwitchOutEnabled(context, it)
                            }
                        )
                    }
                    if (switchOut) {
                        // 切回目标：上一个键盘，或系统已启用的某个指定输入法
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.keyboard_switch_out_target),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    switchOutTarget = DatabaseManager.SWITCH_OUT_TARGET_PREVIOUS
                                    DatabaseManager.setSwitchOutTarget(context, switchOutTarget)
                                }
                            ) {
                                RadioButton(
                                    selected = switchOutTarget == DatabaseManager.SWITCH_OUT_TARGET_PREVIOUS,
                                    onClick = {
                                        switchOutTarget = DatabaseManager.SWITCH_OUT_TARGET_PREVIOUS
                                        DatabaseManager.setSwitchOutTarget(context, switchOutTarget)
                                    }
                                )
                                Text(stringResource(R.string.keyboard_switch_out_target_previous))
                            }
                            imeList.forEach { (id, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        switchOutTarget = id
                                        DatabaseManager.setSwitchOutTarget(context, id)
                                    }
                                ) {
                                    RadioButton(
                                        selected = switchOutTarget == id,
                                        onClick = {
                                            switchOutTarget = id
                                            DatabaseManager.setSwitchOutTarget(context, id)
                                        }
                                    )
                                    Text(label)
                                }
                            }
                        }
                        // 选择器切入宽限期步进器（0-60 秒）
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.keyboard_switch_out_grace))
                                Text(
                                    stringResource(R.string.keyboard_switch_out_grace_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (graceSeconds > 0) {
                                            graceSeconds--
                                            DatabaseManager.setPickerGraceSeconds(context, graceSeconds)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Remove, contentDescription = null)
                                }
                                Text(
                                    stringResource(R.string.keyboard_grace_seconds, graceSeconds),
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = {
                                        if (graceSeconds < 60) {
                                            graceSeconds++
                                            DatabaseManager.setPickerGraceSeconds(context, graceSeconds)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }

            // 6. 防截屏说明
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MimaShapes.card,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.keyboard_secure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
