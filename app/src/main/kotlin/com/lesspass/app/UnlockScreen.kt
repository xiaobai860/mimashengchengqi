package com.lesspass.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lesspass.app.data.DatabaseManager
import com.lesspass.app.data.CredentialStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Composable
fun UnlockScreen(
    dbManager: DatabaseManager,
    credentialStore: CredentialStore,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    var databasePassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val hasDatabase by remember(dbManager.hasDatabase) { derivedStateOf { dbManager.hasDatabase } }
    android.util.Log.d("MimaDB", "UnlockScreen recompose: hasDatabase=$hasDatabase dbManager.hasDatabase=${dbManager.hasDatabase}")

    val executor: Executor = remember { Executors.newSingleThreadExecutor() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.unlock_heading),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasDatabase) stringResource(R.string.unlock_subtitle_has) else stringResource(R.string.unlock_subtitle_create),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = databasePassword,
            onValueChange = { databasePassword = it },
            label = { Text(stringResource(R.string.vault_password_create_hint)) },
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )

        Spacer(Modifier.height(8.dp))

        if (hasDatabase) {
            BiometricButton(
                context = context,
                dbManager = dbManager,
                credentialStore = credentialStore,
                vaultId = dbManager.vaultId,
                executor = executor,
                databasePassword = databasePassword,
                onUnlocked = {
                    onUnlocked()
                },
                onFailed = { errorMessage = it }
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                isLoading = true
                errorMessage = null

                if (hasDatabase) {
                    if (dbManager.openDatabase(databasePassword)) {
                        onUnlocked()
                    } else {
                        isLoading = false
                        errorMessage = context.getString(R.string.wrong_password)
                    }
                } else {
                    if (dbManager.createDatabase(databasePassword)) {
                        onUnlocked()
                    } else {
                        isLoading = false
                        errorMessage = context.getString(R.string.create_vault_failed)
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (hasDatabase) stringResource(R.string.unlock_button) else stringResource(R.string.create_vault_button), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        errorMessage?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BiometricButton(
    context: Context,
    dbManager: DatabaseManager,
    credentialStore: CredentialStore,
    vaultId: String,
    executor: Executor,
    databasePassword: String,
    onUnlocked: () -> Unit,
    onFailed: (String) -> Unit,
) {
    val biometricManager = BiometricManager.from(context)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS

    if (!canAuthenticate) return

    val activity = context as? androidx.fragment.app.FragmentActivity
    if (activity == null) return

    val fingerprintSet = credentialStore.hasFingerprintPassword(vaultId)

    OutlinedButton(
        onClick = {
            if (fingerprintSet) {
                // 已设置指纹：验证指纹后取出保存的密码直接解锁
                credentialStore.decryptFingerprint(
                    vaultId = vaultId,
                    activity = activity,
                    onSuccess = { pwd ->
                        if (dbManager.openDatabase(pwd)) {
                            onUnlocked()
                        } else {
                            onFailed(context.getString(R.string.unlock_failed_retry))
                        }
                    },
                    onError = { onFailed(it) }
                )
            } else {
                onFailed(context.getString(R.string.fingerprint_not_set_hint))
            }
        },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(4.dp)
    ) {
        Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (fingerprintSet) stringResource(R.string.biometric_unlock) else stringResource(R.string.biometric_unlock_not_set))
    }
}
