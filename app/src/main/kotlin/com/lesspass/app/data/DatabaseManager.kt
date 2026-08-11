package com.lesspass.app.data

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.kunzisoft.keepass.database.crypto.kdf.KdfFactory
import com.kunzisoft.keepass.database.element.MasterCredential
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.kunzisoft.keepass.database.file.input.DatabaseInputKDBX
import com.kunzisoft.keepass.database.file.output.DatabaseOutputKDBX
import com.kunzisoft.keepass.utils.UnsignedInt
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 密码本数据库管理器 — 基于 KeePassDX 的 KDBX 引擎。
 * 支持创建、打开、保存加密的 .kdbx 文件。
 *
 * 所有数据（密码本 + 历史记录）统一存储在 KDBX 标准格式中：
 * - 密码本条目 → 根分组
 * - 历史记录条目 → "历史记录" 分组
 *
 * 字段映射（符合 KDBX 官方标准，兼容其他 KeePass 应用）：
 *   密码本: Title/UserName/Password/URL/Notes
 *   历史记录: URL=site, UserName=login, Password=password, Notes=主密码+长度
 */
class DatabaseManager(private val context: Context) {

    companion object {
        private const val HISTORY_GROUP_TITLE = "历史记录"
        private const val MAX_HISTORY = 50
        private const val PREF_NAME = "app_prefs"
        private const val KEY_HAS_PASSWORD = "has_password"
        private const val KEY_DB_PATH = "db_path"
        private const val KEY_DB_URI = "db_uri"
        private const val KEY_DB_DISPLAY_PATH = "db_display_path"
        private const val KEY_HAS_DB = "has_db"
        private const val KEY_AUTO_UNLOCK = "auto_unlock"
        private const val KEY_DB_EXTERNAL_URI = "db_external_uri"
        private const val KEY_CURRENT_DB_FILE = "current_db_file"
        private val HardwareKeyNoOp: (com.kunzisoft.keepass.hardware.HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val defaultDbFile: File
        get() = File(context.filesDir, "password_book.kdbx")

    /** 优先使用 URI（从文件选择器选取），否则回退到文件路径 */
    private val dbUri: Uri?
        get() {
            val uriStr = prefs(context).getString(KEY_DB_URI, null)
            return if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null
        }

    /** 外部密码本文件的 URI（用户通过文件选择器选取的 .kdbx） */
    private val dbExternalUri: Uri?
        get() {
            val uriStr = prefs(context).getString(KEY_DB_EXTERNAL_URI, null)
            return if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null
        }

    private val dbFile: File
        get() = File(prefs(context).getString(KEY_DB_PATH, defaultDbFile.absolutePath) ?: defaultDbFile.absolutePath)

    private var database: DatabaseKDBX? = null
    private var isUnlocked = false
    private var savedMasterPassword: String? = null

    val unlocked: Boolean get() = isUnlocked
    val autoUnlock: Boolean get() = prefs(context).getBoolean(KEY_AUTO_UNLOCK, false)
    /** 当前已选中的密码本文件路径 */
    val currentKdbxFile: File? get() {
        val path = prefs(context).getString(KEY_CURRENT_DB_FILE, null)
        return if (!path.isNullOrBlank()) File(path) else null
    }
    /** 通过 SAF 选取的文件夹 URI（非 SAF 场景为 null） */
    val listFolderUri: Uri? get() = dbUri
    /** 当前打开/选中的密码本文件 URI（仅 SAF 文件有值） */
    val currentKdbxUri: Uri? get() = dbExternalUri
    /** 计算并缓存可读路径 */
    private fun updateDisplayPath() {
        val uri = dbUri
        val current = prefs(context).getString(KEY_DB_DISPLAY_PATH, null)
        val computed = uri?.let { u ->
            val fullPath = java.net.URLDecoder.decode(u.path ?: return@let null, "UTF-8")
            val segments = fullPath.split("/").filter { it.isNotEmpty() }
            val treeIdx = segments.indexOf("tree")
            if (treeIdx >= 0 && treeIdx + 1 < segments.size) {
                val docId = segments.subList(treeIdx + 1, segments.size).joinToString("/").removePrefix("primary:")
                "内部存储/$docId/"
            } else null
        }
        if (computed != null && current != computed) {
            prefs(context).edit().putString(KEY_DB_DISPLAY_PATH, computed).apply()
            Log.d("MimaDB", "updateDisplayPath: $current -> $computed")
        }
    }

    /** 当前使用的密码本文件路径（外部或内置） */
    val filePath: String get() {
        // 优先使用缓存的显示路径
        val cachedPath = prefs(context).getString(KEY_DB_DISPLAY_PATH, null)
        if (!cachedPath.isNullOrBlank() && !cachedPath.contains("document/")) {
            return cachedPath
        }
        
        // 如果缓存路径包含 document/（错误存储），重新计算
        val uri = dbUri
        if (uri != null) {
            val computedPath = computeDisplayPath(uri)
            // 更新缓存
            prefs(context).edit()
                .putString(KEY_DB_DISPLAY_PATH, computedPath)
                .apply()
            return computedPath
        }
        
        // 回退到本地文件路径
        return dbFile.absolutePath
    }
    val exists: Boolean get() = dbFile.exists() || dbUri != null
    val masterPasswordValue: String? get() = savedMasterPassword
    val hasPassword: Boolean get() = prefs(context).getBoolean(KEY_HAS_PASSWORD, false)
    val hasDatabase: Boolean get() = prefs(context).getBoolean(KEY_HAS_DB, false)

    /** 默认密码本文件名（本地存储场景） */
    val defaultKdbxName: String get() = defaultDbFile.name

    /**
     * 是否已显式选中某个密码本文件（通过选择/切换操作）。
     * 首次进入应用、未做过任何选择时可能为 false。
     */
    val hasExplicitKdbxSelection: Boolean
        get() = currentKdbxUri != null || currentKdbxFile != null

    /**
     * 当前应被标识为"选中"的密码本文件（用于 UI 高亮）。
     * 若有显式选中的文件则返回其路径，否则回退到默认密码本文件（若存在）。
     */
    val effectiveSelectedFile: File?
        get() = currentKdbxFile ?: if (defaultDbFile.exists()) defaultDbFile else null

    private fun setHasPassword(hasPassword: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAS_PASSWORD, hasPassword).apply()
    }

    private fun setHasDatabase(hasDatabase: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAS_DB, hasDatabase).apply()
    }

    private fun setDbPath(path: String) {
        prefs(context).edit().putString(KEY_DB_PATH, path).apply()
        prefs(context).edit().remove(KEY_DB_URI).apply()
        prefs(context).edit().remove(KEY_DB_DISPLAY_PATH).apply()
    }

    /** 从 URI 计算可读路径（支持文件夹 URI 和文件 URI） */
    private fun computeDisplayPath(uri: Uri): String {
        val path = uri.path ?: return uri.toString()
        val fullPath = java.net.URLDecoder.decode(path, "UTF-8")
        val segments = fullPath.split("/").filter { it.isNotEmpty() }
        
        // 查找 tree（文件夹 URI）或 document（文件 URI）标识
        val treeIdx = segments.indexOf("tree")
        val docIdx = segments.indexOf("document")
        
        // 确定起始索引
        val startIdx: Int = when {
            treeIdx >= 0 -> treeIdx + 1
            docIdx >= 0 -> docIdx + 1
            else -> return uri.toString()
        }
        
        if (startIdx >= segments.size) return uri.toString()
        
        val remaining = segments.subList(startIdx, segments.size).toMutableList()
        // 移除 primary: 前缀
        if (remaining.isNotEmpty() && remaining[0].startsWith("primary:")) {
            remaining[0] = remaining[0].removePrefix("primary:")
        } else if (remaining.isNotEmpty() && remaining[0].startsWith("secondary:")) {
            remaining[0] = remaining[0].removePrefix("secondary:")
        }
        
        return "内部存储/${remaining.joinToString("/")}"
    }

    /** 用 URI 打开数据库（适用于文件选择器选取的文件） */
    private fun openDatabaseByUri(uri: Uri, password: String): Boolean {
        return try {
            val db = DatabaseKDBX()
            val input = DatabaseInputKDBX(db)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.openDatabase(
                    stream,
                    null,
                    assignMasterKey = {
                        if (password.isNotEmpty()) {
                            val mc = MasterCredential(password.toCharArray())
                            db.deriveMasterKey(mc, HardwareKeyNoOp)
                        }
                    }
                )
            }
            database = db
            isUnlocked = true
            savedMasterPassword = if (password.isNotEmpty()) password else null
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "openDatabaseByUri failed", e)
            isUnlocked = false
            database = null
            savedMasterPassword = null
            false
        }
    }

    /** 用 URI 保存数据库 */
    private fun saveDatabaseByUri(uri: Uri): Boolean {
        return try {
            val db = database ?: return false
            val rootEntries = db.rootGroup?.let { root ->
                val all = mutableListOf<EntryKDBX>()
                collectEntries(root, all)
                all.size
            } ?: 0
            Log.d("MimaDB", "saveDatabaseByUri: start, entries=$rootEntries")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val output = DatabaseOutputKDBX(db)
                output.writeDatabase(out) { }
            }
            Log.d("MimaDB", "saveDatabaseByUri: success")
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "saveDatabaseByUri failed", e)
            false
        }
    }

    /**
     * 创建新数据库。password 为空时不加密。
     */
    fun createDatabase(password: String): Boolean {
        return try {
            // 若默认密码本文件已存在（如清除数据后旧文件被保留），先将其重命名为唯一名称，
            // 避免新建时直接覆盖用户之前的密码本数据。
            if (dbFile.exists()) {
                val renamed = renameFileToAvoidOverwrite(dbFile)
                Log.d("MimaDB", "createDatabase: existing default db renamed=$renamed to avoid overwrite")
            }
            val db = DatabaseKDBX("密码本", "根目录")
            db.kdbxVersion = UnsignedInt(0x40) // KDBX 4.0
            db.kdfEngine = KdfFactory.argon2idKdf
            db.randomizeKdfParameters()
            ensureHistoryGroupExists(db)

            // 先保存到临时变量，只有成功后才提交状态变更
            val hasPw = password.isNotEmpty()
            if (hasPw) {
                val mc = MasterCredential(password.toCharArray())
                db.deriveMasterKey(mc, HardwareKeyNoOp)
            }
            database = db
            val saved = saveDatabase()
            if (saved) {
                isUnlocked = true
                savedMasterPassword = if (hasPw) password else null
                setHasPassword(hasPw)
                setHasDatabase(true)
                if (!hasPw) {
                    prefs(context).edit().putBoolean(KEY_AUTO_UNLOCK, true).apply()
                } else {
                    prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
                }
                Log.d("MimaDB", "createDatabase: saved=$saved")
            } else {
                // 保存失败，回滚状态
                database = null
                isUnlocked = false
                Log.e("MimaDB", "createDatabase: save failed, rolled back")
            }
            saved
        } catch (e: Exception) {
            Log.e("MimaDB", "createDatabase failed", e)
            database = null
            isUnlocked = false
            false
        }
    }

    /**
     * 用密码打开数据库。优先尝试外部 URI → 内置 URI → 本地文件路径。
     * password 为空时直接打开（无加密）。
     */
    fun openDatabase(password: String): Boolean {
        return try {
            // 1. 优先尝试外部 URI（用户通过文件管理选择的 .kdbx）
            val externalUri = dbExternalUri
            if (externalUri != null) {
                Log.d("MimaDB", "openDatabase: trying external URI=$externalUri")
                if (openDatabaseByUri(externalUri, password)) return true
            }
            // 2. 尝试内置 URI（修改保存位置后）
            val uri = dbUri
            if (uri != null) {
                Log.d("MimaDB", "openDatabase: trying URI=$uri")
                if (openDatabaseByUri(uri, password)) return true
            }
            // 3. 回退到本地文件路径
            if (!dbFile.exists()) {
                Log.d("MimaDB", "openDatabase: dbFile does not exist at ${dbFile.absolutePath}")
                return false
            }
            Log.d("MimaDB", "openDatabase: dbFile size=${dbFile.length()} at ${dbFile.absolutePath}")

            val db = DatabaseKDBX()
            val input = DatabaseInputKDBX(db)
            FileInputStream(dbFile).use { stream ->
                input.openDatabase(
                    stream,
                    null,
                    assignMasterKey = {
                        if (password.isNotEmpty()) {
                            val mc = MasterCredential(password.toCharArray())
                            db.deriveMasterKey(mc, HardwareKeyNoOp)
                        }
                    }
                )
            }

            database = db
            isUnlocked = true
            savedMasterPassword = if (password.isNotEmpty()) password else null
            setHasPassword(password.isNotEmpty())
            if (!password.isNotEmpty()) {
                prefs(context).edit().putBoolean(KEY_AUTO_UNLOCK, true).apply()
            } else {
                prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
            }
            updateDisplayPath()
            setHasDatabase(true)
            val rootEntries = db.rootGroup?.let { root ->
                val all = mutableListOf<EntryKDBX>()
                collectEntries(root, all)
                all.size
            } ?: 0
            Log.d("MimaDB", "openDatabase: success, total entries loaded=$rootEntries")
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "openDatabase failed", e)
            isUnlocked = false
            database = null
            savedMasterPassword = null
            false
        }
    }

    /**
     * 修改 KDBX 文件密码。
     * 原理：用新密码重新 deriveMasterKey，然后 saveDatabase() 自动用新密钥重新加密整个文件。
     * 不需要重建数据库或迁移数据。
     *
     * 空 newPassword 表示移除加密（masterKey 设为全零，与 openDatabase 空密码行为一致）。
     * oldPassword 可选：如果提供了且与当前密码不一致，先验证旧密码。
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            // 如果 database 为 null，尝试自动解锁
            if (database == null) {
                val pw = savedMasterPassword ?: ""
                // 如果有旧密码且与当前保存的不一致，用旧密码解锁
                if (oldPassword.isNotEmpty() && hasPassword && oldPassword != pw) {
                    if (!openDatabase(oldPassword)) return false
                } else {
                    // 无密码或密码匹配，用保存的密码解锁
                    if (!openDatabase(pw)) return false
                }
            }

            val db = database ?: run {
                Log.e("MimaDB", "changePassword: database is null")
                return false
            }

            // 用新密码重新派生主密钥
            if (newPassword.isNotEmpty()) {
                val mc = MasterCredential(newPassword.toCharArray())
                db.deriveMasterKey(mc, HardwareKeyNoOp)
            } else {
                db.masterKey = ByteArray(32)
            }
            savedMasterPassword = newPassword
            setHasPassword(newPassword.isNotEmpty())
            if (!newPassword.isNotEmpty()) {
                prefs(context).edit().putBoolean(KEY_AUTO_UNLOCK, true).apply()
            } else {
                prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
            }

            saveDatabase()
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "changePassword failed", e)
            false
        }
    }

    private fun passwordNullOrBlank(pw: String?): Boolean = pw.isNullOrBlank()

    /**
     * 保存数据库到文件
     */
    fun saveDatabase(): Boolean {
        return try {
            val db = database ?: return false
            val rootEntries = db.rootGroup?.let { root ->
                val all = mutableListOf<EntryKDBX>()
                collectEntries(root, all)
                all.size
            } ?: 0
            Log.d("MimaDB", "saveDatabase: start, total entries in memory=$rootEntries, masterKey set=${db.masterKey != null}, kdfParams set=${db.kdfParameters != null}")
            FileOutputStream(dbFile).use { stream ->
                val output = DatabaseOutputKDBX(db)
                output.writeDatabase(stream) { /* reuse existing key */ }
            }
            Log.d("MimaDB", "saveDatabase: success, file size=${dbFile.length()}")
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "saveDatabase failed: ${e.message}", e)
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                Log.e("MimaDB", "  cause[$depth]: ${cause::class.simpleName}: ${cause.message}", cause)
                cause = cause.cause
                depth++
            }
            false
        }
    }

    /**
     * 将数据库写入指定输出流（用于内存生成文件后分享/保存）
     */
    fun exportToOutputStream(out: java.io.OutputStream): Boolean {
        return try {
            val db = database ?: return false
            val output = DatabaseOutputKDBX(db)
            output.writeDatabase(out) { }
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "exportToOutputStream failed", e)
            false
        }
    }

    /**
     * 导出 KDBX 文件到指定路径
     */
    fun exportDatabase(targetPath: String): Boolean {
        return try {
            val db = database ?: return false
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { stream ->
                val output = DatabaseOutputKDBX(db)
                output.writeDatabase(stream) { /* reuse existing key */ }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 修改 KDBX 文件保存位置
     */
    fun moveDatabase(newPath: String): Boolean {
        return try {
            val targetFile = File(newPath)
            targetFile.parentFile?.mkdirs()
            if (database != null) {
                saveDatabase()
            }
            if (dbFile.exists()) {
                dbFile.copyTo(targetFile, overwrite = true)
                dbFile.delete()
            }
            setDbPath(newPath)
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "moveDatabase failed", e)
            false
        }
    }

    /**
     * 清除旧路径配置（保留当前选中文件的 URI）
     * @param keepExternalUri 是否保留外部文件 URI（用于迁移场景）
     */
    fun clearOldPaths(keepExternalUri: Boolean = false) {
        val currentExternalUri = if (keepExternalUri) prefs(context).getString(KEY_DB_EXTERNAL_URI, null) else null
        val currentDbFile = if (keepExternalUri) prefs(context).getString(KEY_CURRENT_DB_FILE, null) else null
        prefs(context).edit().apply {
            remove(KEY_DB_PATH)
            remove(KEY_DB_DISPLAY_PATH)
            if (!keepExternalUri) {
                remove(KEY_DB_EXTERNAL_URI)
                remove(KEY_CURRENT_DB_FILE)
            } else {
                currentExternalUri?.let { putString(KEY_DB_EXTERNAL_URI, it) }
                currentDbFile?.let { putString(KEY_CURRENT_DB_FILE, it) }
            }
            apply()
        }
        Log.d("MimaDB", "clearOldPaths: keepExternalUri=$keepExternalUri")
    }

    /**
     * 修复错误存储的 KEY_DB_URI（之前可能被错误地存储为文件 URI）
     * 如果 KEY_DB_URI 以 "document/" 开头（文件 URI），而不是 "tree/"（文件夹 URI），
     * 则将其清除或从 KEY_DB_EXTERNAL_URI 恢复正确的文件夹路径
     */
    fun fixInvalidDbUriIfNeeded() {
        try {
            val dbUriStr = prefs(context).getString(KEY_DB_URI, null)
            Log.d("MimaDB", "fixInvalidDbUriIfNeeded: dbUriStr=$dbUriStr")
            if (dbUriStr == null) {
                Log.d("MimaDB", "fixInvalidDbUriIfNeeded: no dbUri, skipping")
                return
            }
            
            val uri = Uri.parse(dbUriStr)
            val path = uri.path ?: run {
                Log.d("MimaDB", "fixInvalidDbUriIfNeeded: no path, skipping")
                return
            }
            Log.d("MimaDB", "fixInvalidDbUriIfNeeded: path=$path")
            
            // 检查是否为文件 URI（路径中包含 /document/）
            if (path.contains("/document/")) {
                Log.w("MimaDB", "fixInvalidDbUriIfNeeded: KEY_DB_URI was incorrectly stored as file URI, fixing...")
                
                // 先清除错误的显示路径
                prefs(context).edit()
                    .remove(KEY_DB_DISPLAY_PATH)
                    .apply()
                
                // 如果同时有外部文件 URI，从文件 URI 推断文件夹 URI
                val extUriStr = prefs(context).getString(KEY_DB_EXTERNAL_URI, null)
                if (extUriStr != null) {
                    val extUri = Uri.parse(extUriStr)
                    val extPath = extUri.path ?: ""
                    
                    // 从文件路径推断文件夹路径
                    // 文件路径格式: /document/primary:Download/密码本/file.kdbx
                    // 文件夹路径格式: /tree/primary:Download/密码本
                    val docIdx = extPath.indexOf("/document/")
                    if (docIdx >= 0) {
                        val beforeDoc = extPath.substring(0, docIdx)
                        val afterDoc = extPath.substring(docIdx + "/document/".length)
                        val segments = afterDoc.split("/").filter { it.isNotEmpty() }
                        // 移除最后一段（文件名），剩余部分为文件夹
                        val folderSegments = segments.dropLast(1)
                        if (folderSegments.isNotEmpty()) {
                            val folderPath = "$beforeDoc/tree/${folderSegments.joinToString("/")}"
                            val fixedUri = uri.buildUpon().path(folderPath).build()
                            Log.d("MimaDB", "fixInvalidDbUriIfNeeded: fixed to $fixedUri")
                            
                            prefs(context).edit()
                                .putString(KEY_DB_URI, fixedUri.toString())
                                .apply()
                            
                            // 更新显示路径
                            val displayPath = computeDisplayPath(fixedUri)
                            prefs(context).edit()
                                .putString(KEY_DB_DISPLAY_PATH, displayPath)
                                .apply()
                            return
                        }
                    }
                }
                
                // 如果无法推断，清除错误的 URI（用户需要重新选择文件夹）
                Log.w("MimaDB", "fixInvalidDbUriIfNeeded: clearing invalid KEY_DB_URI, user needs to re-select")
                prefs(context).edit()
                    .remove(KEY_DB_URI)
                    .remove(KEY_DB_DISPLAY_PATH)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e("MimaDB", "fixInvalidDbUriIfNeeded failed", e)
        }
    }

    /**
     * 检查目标文件夹中是否存在同名文件，返回可用的文件名
     * @param folderUri 目标文件夹 URI
     * @param originalName 原始文件名
     * @return 可用的文件名（如 originalName, originalName(1), originalName(2)...）
     */
    fun getAvailableFileName(folderUri: Uri, originalName: String): String {
        try {
            val parent = DocumentFile.fromTreeUri(context, folderUri)
            if (parent == null) return originalName
            val existingNames = parent.listFiles()
                .filter { it.isFile && it.name?.endsWith(".kdbx", ignoreCase = true) == true }
                .mapNotNull { it.name }
                .toSet()
            
            if (originalName !in existingNames) return originalName
            
            // 生成重命名方案：name(1).kdbx, name(2).kdbx
            val baseName = originalName.removeSuffix(".kdbx")
            var counter = 1
            while (counter <= 100) {
                val newName = "${baseName}($counter).kdbx"
                if (newName !in existingNames) return newName
                counter++
            }
            return originalName
        } catch (e: Exception) {
            Log.e("MimaDB", "getAvailableFileName failed", e)
            return originalName
        }
    }

    /**
     * 将已存在的文件重命名为不冲突的唯一名称（append (n)），避免被新建操作覆盖。
     * 返回是否成功重命名。
     */
    private fun renameFileToAvoidOverwrite(file: File): Boolean {
        return try {
            val name = file.name
            val base = name.removeSuffix(".kdbx")
            var counter = 1
            var target: File
            do {
                target = File(file.parentFile, "${base}($counter).kdbx")
                counter++
            } while (target.exists() && counter <= 100)
            file.renameTo(target)
        } catch (e: Exception) {
            Log.e("MimaDB", "renameFileToAvoidOverwrite failed", e)
            false
        }
    }

    /**
     * 将当前选中的文件迁移到新文件夹
     * @param newFolderUri 新文件夹 URI
     * @return Triple<Boolean, String, String> (是否成功, 新文件名, 错误信息)
     */
    fun migrateCurrentFileToFolder(newFolderUri: Uri): Triple<Boolean, String, String> {
        return try {
            // 检查数据库是否已加载
            if (!isUnlocked || database == null) {
                val msg = "数据库未解锁，请先打开密码本后再尝试迁移"
                Log.e("MimaDB", "migrateCurrentFileToFolder: $msg")
                return Triple(false, "", msg)
            }
            
            // 获取原文件名（从外部 URI 或本地文件）
            val sourceUri = dbExternalUri
            val originalName = when {
                sourceUri != null -> {
                    val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                    sourceDoc?.name ?: sourceUri.lastPathSegment ?: "password_book.kdbx"
                }
                currentKdbxFile != null -> {
                    currentKdbxFile?.name ?: "password_book.kdbx"
                }
                else -> "password_book.kdbx"
            }
            Log.d("MimaDB", "migrateCurrentFileToFolder: source=$sourceUri, name=$originalName")
            
            // 清理旧路径（保留外部 URI 以便迁移后更新）
            clearOldPaths(keepExternalUri = true)
            
            // 获取可用的新文件名（处理同名冲突）
            val newName = getAvailableFileName(newFolderUri, originalName)
            Log.d("MimaDB", "migrateCurrentFileToFolder: original=$originalName, available=$newName")
            
            // 在新文件夹创建文件
            val parentDir = DocumentFile.fromTreeUri(context, newFolderUri)
                ?: run {
                    val msg = "无法访问目标文件夹，请重新选择"
                    Log.e("MimaDB", "migrateCurrentFileToFolder: $msg")
                    return Triple(false, "", msg)
                }
            val mimeType = "application/octet-stream"
            // 注意：createFile 的 displayName 必须保留 .kdbx 后缀，否则在部分 ROM（如小米）
            // 上文件会丢失扩展名，导致后续按 .kdbx 列举时无法识别。
            val createName = if (newName.endsWith(".kdbx", ignoreCase = true)) newName else "$newName.kdbx"
            val newFile = try {
                parentDir.createFile(mimeType, createName)
            } catch (e: Exception) {
                Log.e("MimaDB", "migrateCurrentFileToFolder: createFile failed", e)
                null
            }
            if (newFile == null) {
                val msg = "无法在目标文件夹中创建文件，请检查文件夹权限"
                Log.e("MimaDB", "migrateCurrentFileToFolder: $msg")
                return Triple(false, "", msg)
            }
            
            // 将数据库写入新文件
            val db = database!!
            val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                ?: run {
                    val msg = "无法打开目标文件进行写入"
                    Log.e("MimaDB", "migrateCurrentFileToFolder: $msg")
                    return Triple(false, "", msg)
                }
            outputStream.use { out ->
                val output = DatabaseOutputKDBX(db)
                output.writeDatabase(out) { }
            }
            Log.d("MimaDB", "migrateCurrentFileToFolder: wrote database to ${newFile.uri}")
            
            // 更新路径配置：设置新文件夹为保存位置，新文件为当前选中文件
            val displayPath = computeDisplayPath(newFolderUri)
            prefs(context).edit()
                .putString(KEY_DB_URI, newFolderUri.toString())
                .putString(KEY_DB_EXTERNAL_URI, newFile.uri.toString())
                .putString(KEY_DB_DISPLAY_PATH, displayPath)
                .putString(KEY_CURRENT_DB_FILE, newFile.uri.path ?: newFile.uri.toString())
                .apply()
            
            updateDisplayPath()
            setHasDatabase(true)
            
            // 申请持久化权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    newFolderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                context.contentResolver.takePersistableUriPermission(
                    newFile.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("MimaDB", "migrateCurrentFileToFolder: takePersistableUriPermission failed", e)
            }
            
            Log.d("MimaDB", "migrateCurrentFileToFolder: success, newName=$newName")
            Triple(true, newName, "")
        } catch (e: Exception) {
            Log.e("MimaDB", "migrateCurrentFileToFolder failed", e)
            Triple(false, "", "迁移失败：${e.message}")
        }
    }

    /**
     * 在指定文件夹创建新的空密码本
     * @param folderUri 文件夹 URI
     * @param fileName 文件名（不含扩展名）
     * @param password 密码（可为空表示无加密）
     * @return Triple<Boolean, Uri?, String> (是否成功, 新文件 URI, 错误信息)
     */
    fun createNewKdbxInFolder(folderUri: Uri, fileName: String = "password_book", password: String = ""): Triple<Boolean, Uri?, String> {
        return try {
            // 清理旧路径
            clearOldPaths(keepExternalUri = false)
            
            // 在指定文件夹创建文件
            val parentDir = DocumentFile.fromTreeUri(context, folderUri)
                ?: run {
                    val msg = "无法访问目标文件夹，请重新选择"
                    Log.e("MimaDB", "createNewKdbxInFolder: $msg")
                    return Triple(false, null, msg)
                }
            
            // 处理文件名冲突
            val fullFileName = if (fileName.endsWith(".kdbx")) fileName else "$fileName.kdbx"
            val availableName = getAvailableFileName(folderUri, fullFileName)
            // createFile 的 displayName 必须保留 .kdbx 后缀，否则部分 ROM 上文件会丢失扩展名。
            val createName = availableName

            val mimeType = "application/octet-stream"
            val newFile = try {
                parentDir.createFile(mimeType, createName)
            } catch (e: Exception) {
                Log.e("MimaDB", "createNewKdbxInFolder: createFile failed", e)
                null
            }
            if (newFile == null) {
                val msg = "无法在目标文件夹中创建文件，请检查文件夹权限"
                Log.e("MimaDB", "createNewKdbxInFolder: $msg")
                return Triple(false, null, msg)
            }
            
            // 创建新数据库
            val db = DatabaseKDBX("密码本", "根目录")
            db.kdbxVersion = UnsignedInt(0x40) // KDBX 4.0
            db.kdfEngine = KdfFactory.argon2idKdf
            db.randomizeKdfParameters()
            
            if (password.isNotEmpty()) {
                val mc = MasterCredential(password.toCharArray())
                db.deriveMasterKey(mc, HardwareKeyNoOp)
                setHasPassword(true)
            } else {
                setHasPassword(false)
            }
            
            ensureHistoryGroupExists(db)
            
            // 先设置为当前数据库
            database = db
            isUnlocked = true
            savedMasterPassword = if (password.isNotEmpty()) password else null
            
            // 直接写入到目标位置
            val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                ?: run {
                    val msg = "无法打开目标文件进行写入"
                    Log.e("MimaDB", "createNewKdbxInFolder: $msg")
                    newFile.delete()
                    database = null
                    isUnlocked = false
                    return Triple(false, null, msg)
                }
            outputStream.use { out ->
                val output = DatabaseOutputKDBX(db)
                output.writeDatabase(out) { }
            }
            Log.d("MimaDB", "createNewKdbxInFolder: created ${newFile.uri}")
            
            // 更新路径配置
            val displayPath = computeDisplayPath(folderUri)
            prefs(context).edit()
                .putString(KEY_DB_URI, folderUri.toString())
                .putString(KEY_DB_EXTERNAL_URI, newFile.uri.toString())
                .putString(KEY_DB_DISPLAY_PATH, displayPath)
                .putString(KEY_CURRENT_DB_FILE, newFile.uri.path ?: newFile.uri.toString())
                .apply()
            
            if (!password.isNotEmpty()) {
                prefs(context).edit().putBoolean(KEY_AUTO_UNLOCK, true).apply()
            } else {
                prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
            }
            
            updateDisplayPath()
            setHasDatabase(true)
            
            // 申请持久化权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                context.contentResolver.takePersistableUriPermission(
                    newFile.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("MimaDB", "createNewKdbxInFolder: takePersistableUriPermission failed", e)
            }
            
            Log.d("MimaDB", "createNewKdbxInFolder: success")
            Triple(true, newFile.uri, "")
        } catch (e: Exception) {
            Log.e("MimaDB", "createNewKdbxInFolder failed", e)
            Triple(false, null, "创建失败：${e.message}")
        }
    }

    /** 用文件夹 URI 迁移数据库（保留向后兼容） */
    fun moveDatabaseByUri(folderUri: Uri): Boolean {
        return try {
            // 先保存当前数据库
            if (database != null) {
                saveDatabase()
            }
            // 如果有 URI 路径，也保存一次
            val uri = dbUri
            if (uri != null && uri != folderUri) {
                saveDatabaseByUri(uri)
            }
            // 用 DocumentFile 在目标文件夹中创建文件（兼容性更好）
            val parentDir = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw IOException("无法访问目标文件夹")
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("kdbx") ?: "application/octet-stream"
            val newFile = parentDir.createFile(mimeType, "password_book")
                ?: throw IOException("无法在目标文件夹中创建文件")
            // 把当前数据库数据写入新文件
            if (database != null) {
                val db = database!!
                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    val output = DatabaseOutputKDBX(db)
                    output.writeDatabase(out) { }
                }
            }
            // 提取可读路径并存入 SharedPreferences
            val displayPath = computeDisplayPath(folderUri)
            // 持久化新文件夹 URI
            prefs(context).edit()
                .putString(KEY_DB_URI, folderUri.toString())
                .putString(KEY_DB_DISPLAY_PATH, displayPath)
                .apply()
            // 立即刷新缓存（确保 UI 即时更新）
            updateDisplayPath()
            // 申请持久化权限
            context.contentResolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "moveDatabaseByUri failed", e)
            false
        }
    }

    /**
     * 用 URI 打开外部 .kdbx 文件。
     * password 为空表示无密码，若文件实际有密码则返回 false。
     */
    fun openExternalKdbx(uri: Uri, password: String?): Boolean {
        return try {
            val db = DatabaseKDBX()
            val input = DatabaseInputKDBX(db)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.openDatabase(
                    stream,
                    null,
                    assignMasterKey = {
                        if (!password.isNullOrBlank()) {
                            val mc = MasterCredential(password!!.toCharArray())
                            db.deriveMasterKey(mc, HardwareKeyNoOp)
                        }
                    }
                )
            } ?: return false
            database = db
            isUnlocked = true
            savedMasterPassword = if (!password.isNullOrBlank()) password else null
            setHasPassword(!password.isNullOrBlank())
            // 持久化外部文件 URI（不覆盖内置文件路径）
            prefs(context).edit()
                .putString(KEY_DB_EXTERNAL_URI, uri.toString())
                .apply()
            // 申请持久化权限
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "openExternalKdbx failed", e)
            isUnlocked = false
            database = null
            savedMasterPassword = null
            false
        }
    }

    /**
     * 选择密码本文件并解锁。
     * 会持久化当前选中文件的 URI 和路径，确保后续保存操作写入正确文件。
     */
    fun selectKdbxFile(uri: Uri, password: String?): Boolean {
        return try {
            val db = DatabaseKDBX()
            val input = DatabaseInputKDBX(db)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.openDatabase(
                    stream,
                    null,
                    assignMasterKey = {
                        if (!password.isNullOrBlank()) {
                            val mc = MasterCredential(password.toCharArray())
                            db.deriveMasterKey(mc, HardwareKeyNoOp)
                        }
                    }
                )
            } ?: return false
            database = db
            isUnlocked = true
            savedMasterPassword = if (!password.isNullOrBlank()) password else null
            setHasPassword(!password.isNullOrBlank())
            // 持久化：只更新 externalUri（文件）和 currentDbFile
            // KEY_DB_URI 应该保持为文件夹 URI，不应该被文件 URI 覆盖
            prefs(context).edit()
                .putString(KEY_DB_EXTERNAL_URI, uri.toString())
                .putString(KEY_DB_DISPLAY_PATH, computeDisplayPath(uri))
                .putString(KEY_CURRENT_DB_FILE, uri.path ?: uri.toString())
                .apply()
            // 持久化权限：部分 ROM（如小米）对 SAF 文件 URI 不授予 persistable 权限，
            // 此处失败时不应影响解锁结果，故单独 try/catch。
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("MimaDB", "selectKdbxFile: takePersistableUriPermission failed (ignored)", e)
            }
            updateDisplayPath()
            setHasDatabase(true)
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "selectKdbxFile failed", e)
            isUnlocked = false
            database = null
            savedMasterPassword = null
            false
        }
    }

    /** 删除指定的密码本文件，返回是否成功 */
    fun deleteKdbxFile(file: File): Boolean {
        return try {
            // 如果是当前选中的文件，先清除引用
            if (currentKdbxFile?.absolutePath == file.absolutePath) {
                prefs(context).edit()
                    .remove(KEY_CURRENT_DB_FILE)
                    .remove(KEY_DB_EXTERNAL_URI)
                    .apply()
            }
            file.delete()
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "deleteKdbxFile failed", e)
            false
        }
    }

    /**
     * 通过 SAF URI 删除文件。
     * 使用 ContentResolver 的 query 获取 _display_name 和 _id，
     * 构造精确的 ContentProvider URI 进行删除，避免 fromSingleUri 对某些 Provider 失败的问题。
     */
    fun deleteKdbxFileByUri(uri: Uri): Boolean {
        return try {
            Log.d("MimaDB", "deleteKdbxFileByUri: uri=$uri")
            // 通过 ContentResolver 查询文件名
            val displayName = try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            } catch (e: Exception) { null }
            Log.d("MimaDB", "deleteKdbxFileByUri: displayName=$displayName")

            // 方案1：直接用 DocumentFile.fromSingleUri
            val docFile = DocumentFile.fromSingleUri(context, uri)
            Log.d("MimaDB", "deleteKdbxFileByUri: docFile=${docFile?.name}, isFile=${docFile?.isFile}")
            if (docFile != null && docFile.isFile) {
                val deleted = docFile.delete()
                Log.d("MimaDB", "deleteKdbxFileByUri: fromSingleUri delete=$deleted")
                return deleted
            }

            // 方案2：从文件夹 URI 找到文件再删除
            val treeUri = DocumentFile.fromTreeUri(context, uri)
            Log.d("MimaDB", "deleteKdbxFileByUri: treeUri=$treeUri")
            if (treeUri != null) {
                val found = treeUri.listFiles().firstOrNull { it.uri == uri }
                Log.d("MimaDB", "deleteKdbxFileByUri: found=${found?.name}")
                if (found?.isFile == true) {
                    val deleted = found.delete()
                    Log.d("MimaDB", "deleteKdbxFileByUri: fromTreeUri delete=$deleted")
                    return deleted
                }
            }

            // 方案3：通过 ContentResolver 直接删除（构造精确查询条件）
            try {
                val args = if (displayName != null) arrayOf(displayName) else null
                val deleted = args?.let {
                    context.contentResolver.delete(uri, "${android.provider.OpenableColumns.DISPLAY_NAME} = ?", it)
                } ?: 0
                Log.d("MimaDB", "deleteKdbxFileByUri: contentResolver delete(rows)=$deleted")
                deleted > 0
            } catch (e: Exception) {
                Log.e("MimaDB", "deleteKdbxFileByUri: contentResolver delete failed", e)
                false
            }
        } catch (e: Exception) {
            Log.e("MimaDB", "deleteKdbxFileByUri failed for $uri", e)
            false
        }
    }

    /**
     * 列出密码本文件夹中所有 .kdbx 文件。
     * 优先使用 SAF URI 路径，回退到本地文件路径。
     */
    fun listKdbxFiles(): List<KdbxFileInfo> {
        val folderUri = dbUri
        Log.d("MimaDB", "listKdbxFiles: dbUri=$folderUri")
        val result = if (folderUri != null) {
            listKdbxFilesByUri(folderUri)
        } else {
            val folder = currentKdbxFile?.parentFile ?: defaultDbFile.parentFile ?: context.filesDir
            listKdbxFiles(folder)
        }
        Log.d("MimaDB", "listKdbxFiles: result.size=${result.size}")
        return result
    }

    /**
     * 列出指定目录下所有 .kdbx 文件信息。
     * 包含：文件名、完整路径、URI（文件 URI）、是否有密码保护
     */
    fun listKdbxFiles(folder: File): List<KdbxFileInfo> {
        return folder.listFiles { _, name -> name.endsWith(".kdbx", ignoreCase = true) }
            ?.mapNotNull { file ->
                try {
                    val uri = android.net.Uri.fromFile(file)
                    KdbxFileInfo(
                        name = file.name,
                        path = file.absolutePath,
                        uri = uri,
                        hasPassword = false,
                        size = file.length(),
                        modifiedAt = file.lastModified(),
                    )
                } catch (e: Exception) {
                    Log.e("MimaDB", "listKdbxFiles error for ${file.name}", e)
                    null
                }
            }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 通过 SAF URI 列出文件夹内所有 .kdbx 文件。
     *
     * 兼容说明：
     *  - [DocumentFile.listFiles] 在小米等 ROM 上能返回子文档，但 [DocumentFile.getName]
     *    对子文档返回 null（DISPLAY_NAME 列读取异常），导致此前按 .kdbx 过滤后只剩 1 个。
     *  - [DocumentsContract.buildChildDocumentsUriUsingTree] 对含中文路径的 tree 在小米上
     *    只返回目录自身（1 条），同样不可靠。
     *
     * 因此采用组合方案：
     *   1) 用 [DocumentFile.fromTreeUri].listFiles() 拿到真实子文档集合（数量正确）；
     *   2) 对每个 child 提取 documentId，构造标准 document URI（buildDocumentUriUsingTree），
     *      再 query 取 COLUMN_DISPLAY_NAME / MIME_TYPE / SIZE / LAST_MODIFIED（标准 child-document
     *      URI 的 DISPLAY_NAME 在 externalstorage provider 上可读）；
     *   3) DISPLAY_NAME 仍缺失时，回退用 documentId 的末段作为文件名。
     */
    fun listKdbxFilesByUri(folderUri: Uri): List<KdbxFileInfo> {
        return try {
            val parent = DocumentFile.fromTreeUri(context, folderUri)
                ?: return emptyList<KdbxFileInfo>().also { Log.e("MimaDB", "listKdbxFilesByUri: parent null for $folderUri") }
            val children = parent.listFiles()
            Log.d("MimaDB", "listKdbxFilesByUri: folderUri=$folderUri totalChildren=${children.size}")

            val colName = DocumentsContract.Document.COLUMN_DISPLAY_NAME
            val colMime = DocumentsContract.Document.COLUMN_MIME_TYPE
            val colSize = DocumentsContract.Document.COLUMN_SIZE
            val colLastMod = DocumentsContract.Document.COLUMN_LAST_MODIFIED

            val result = mutableListOf<KdbxFileInfo>()
            children.forEachIndexed { i, docFile ->
                try {
                    val docId = DocumentsContract.getDocumentId(docFile.uri)
                    val standardUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    var name: String? = null
                    var mime: String? = null
                    var size = 0L
                    var lastMod = 0L
                    try {
                        context.contentResolver.query(
                            standardUri,
                            arrayOf(colName, colMime, colSize, colLastMod),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                name = c.getString(c.getColumnIndexOrThrow(colName))
                                mime = c.getString(c.getColumnIndexOrThrow(colMime))
                                size = try { c.getLong(c.getColumnIndexOrThrow(colSize)) } catch (_: Exception) { 0L }
                                lastMod = try { c.getLong(c.getColumnIndexOrThrow(colLastMod)) } catch (_: Exception) { 0L }
                            }
                        }
                    } catch (qe: Exception) {
                        Log.w("MimaDB", "listKdbxFilesByUri: query failed for docId=$docId", qe)
                    }
                    if (name.isNullOrBlank()) name = docFile.name
                    if (name.isNullOrBlank()) name = docId.substringAfterLast('/').substringAfterLast(':')
                    Log.d("MimaDB", "child[$i] docId=$docId name=$name mime=$mime size=$size")
                    // 仅跳过目录；部分 ROM（如小米）上 .kdbx 文件的 DISPLAY_NAME 会丢失扩展名，
                    // 因此不再强制 .kdbx 后缀过滤，以避免合法密码本被漏列。
                    if (name.isNullOrBlank()) return@forEachIndexed
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return@forEachIndexed
                    result.add(
                        KdbxFileInfo(
                            name = name!!,
                            path = standardUri.toString(),
                            uri = standardUri,
                            hasPassword = false,
                            size = size,
                            modifiedAt = lastMod,
                            isFromSaf = true,
                        )
                    )
                } catch (e: Exception) {
                    Log.e("MimaDB", "listKdbxFilesByUri error for child[$i]", e)
                }
            }
            Log.d("MimaDB", "listKdbxFilesByUri: matched=${result.size}")
            result.sortedBy { it.name }
        } catch (e: Exception) {
            Log.e("MimaDB", "listKdbxFilesByUri failed", e)
            emptyList()
        }
    }

    data class KdbxFileInfo(
        val name: String,
        val path: String,
        val uri: Uri,
        val hasPassword: Boolean,
        val size: Long = 0L,
        val modifiedAt: Long = 0L,
        val isFromSaf: Boolean = false,
    )

    /**
     * 锁定数据库
     */
    fun lock() {
        database = null
        isUnlocked = false
        savedMasterPassword = null
    }

    /** 重置自动解锁状态（用于设置页面重置密码本） */
    fun resetAutoUnlock() {
        prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
    }

    /** 清除所有应用数据（共享偏好、缓存），保留密码本 kdbx 文件 */
    fun clearAllData(): Boolean {
        return try {
            // 删除所有 SharedPreferences（含主密码、密码本密码、文件保存位置等全部设置）
            prefs(context).edit().clear().apply()
            // 同时清除密码生成界面的独立 SharedPreferences（含生成密码用的主密码等设置）
            context.getSharedPreferences("generate_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            // 删除缓存目录中的临时文件（保留任何 .kdbx 密码本文件）
            context.cacheDir?.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".kdbx", ignoreCase = true)) file.delete()
            }
            // 删除应用私有文件目录中的临时文件，但保留所有 .kdbx 密码本文件，
            // 方便用户清除数据后仍可在列表中重新选择之前的密码本。
            context.filesDir?.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".kdbx", ignoreCase = true)) file.delete()
            }
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "clearAllData failed", e)
            false
        }
    }

    // ==================== 密码本操作 ====================

    fun getPasswordBookEntries(): List<EntryKDBX> {
        val db = database ?: return emptyList()
        val root = db.rootGroup ?: return emptyList()
        val entries = mutableListOf<EntryKDBX>()
        collectEntriesExcluding(root, entries, getHistoryGroup(db))
        return entries
    }

    fun addPasswordBookEntry(title: String, username: String, password: String, url: String = "", notes: String = ""): EntryKDBX? {
        val db = database ?: return null
        val entry = db.createEntry()
        entry.title = title
        entry.username = username
        entry.password = password.toCharArray()
        entry.url = url
        entry.notes = notes
        db.rootGroup?.addChildEntry(entry)
        return entry
    }

    // ==================== 历史记录操作 ====================

    fun addHistoryEntry(site: String, login: String, password: String, masterPassword: String = "", length: Int = 16): Boolean {
        val db = database ?: return false
        val historyGroup = getHistoryGroup(db) ?: return false

        val notes = buildHistoryNotes(masterPassword, length)

        val existingEntries = historyGroup.getChildEntries() as? List<EntryKDBX> ?: emptyList()
        val duplicates = existingEntries.filter { e ->
            e.url == site && e.username == login &&
                String(e.password) == password && e.notes == notes
        }
        for (dup in duplicates) {
            historyGroup.removeChildEntry(dup)
        }

        val entry = db.createEntry()
        entry.url = site
        entry.username = login
        entry.password = password.toCharArray()
        entry.notes = notes
        historyGroup.addChildEntry(entry)

        while (historyGroup.getChildEntries().size > MAX_HISTORY) {
            val children = historyGroup.getChildEntries() as? List<EntryKDBX> ?: break
            if (children.isNotEmpty()) {
                historyGroup.removeChildEntry(children.last())
            } else break
        }

        return true
    }

    fun getHistoryEntries(): List<EntryKDBX> {
        val db = database ?: return emptyList()
        val historyGroup = getHistoryGroup(db) ?: return emptyList()
        return historyGroup.getChildEntries() as? List<EntryKDBX> ?: emptyList()
    }

    fun deleteHistoryEntry(entry: EntryKDBX): Boolean {
        val db = database ?: return false
        val historyGroup = getHistoryGroup(db) ?: return false
        historyGroup.removeChildEntry(entry)
        return true
    }

    // ==================== 通用操作 ====================

    fun getAllEntries(): List<EntryKDBX> {
        val db = database ?: return emptyList()
        val entries = mutableListOf<EntryKDBX>()
        collectEntries(db.rootGroup, entries)
        return entries
    }

    fun getAllGroups(): List<GroupKDBX> {
        val db = database ?: return emptyList()
        val groups = mutableListOf<GroupKDBX>()
        collectGroups(db.rootGroup, groups)
        return groups
    }

    fun deleteEntry(entry: EntryKDBX): Boolean {
        val db = database ?: return false
        val parent = entry.parent as? GroupKDBX
        parent?.removeChildEntry(entry)
        return true
    }

    fun getRootGroup(): GroupKDBX? = database?.rootGroup
    fun getDatabase(): DatabaseKDBX? = database

    // ==================== 内部工具 ====================

    private fun getHistoryGroup(db: DatabaseKDBX): GroupKDBX? {
        return findGroupByTitle(db.rootGroup, HISTORY_GROUP_TITLE)
            ?: ensureHistoryGroupExists(db)
    }

    private fun ensureHistoryGroupExists(db: DatabaseKDBX): GroupKDBX? {
        val root = db.rootGroup ?: return null
        val existing = findGroupByTitle(root, HISTORY_GROUP_TITLE)
        if (existing != null) return existing

        val group = db.createGroup()
        group.title = HISTORY_GROUP_TITLE
        root.addChildGroup(group)
        return group
    }

    private fun findGroupByTitle(parent: GroupKDBX?, title: String): GroupKDBX? {
        if (parent == null) return null
        val groups = parent.getChildGroups() as? List<GroupKDBX> ?: return null
        return groups.firstOrNull { it.title == title }
    }

    private fun buildHistoryNotes(masterPassword: String, length: Int): String {
        val sb = StringBuilder()
        if (masterPassword.isNotEmpty()) {
            sb.append("主密码: ").append(masterPassword)
        }
        sb.append("\n长度: ").append(length)
        return sb.toString()
    }

    private fun collectEntries(group: GroupKDBX?, result: MutableList<EntryKDBX>) {
        group?.let {
            val entries = it.getChildEntries() as? List<EntryKDBX> ?: return@let
            result.addAll(entries)
            val groups = it.getChildGroups() as? List<GroupKDBX> ?: return@let
            for (childGroup in groups) {
                collectEntries(childGroup, result)
            }
        }
    }

    private fun collectEntriesExcluding(group: GroupKDBX?, result: MutableList<EntryKDBX>, excludeGroup: GroupKDBX?) {
        group?.let {
            if (it === excludeGroup) return@let
            val entries = it.getChildEntries() as? List<EntryKDBX> ?: return@let
            result.addAll(entries)
            val groups = it.getChildGroups() as? List<GroupKDBX> ?: return@let
            for (childGroup in groups) {
                collectEntriesExcluding(childGroup, result, excludeGroup)
            }
        }
    }

    private fun collectGroups(group: GroupKDBX?, result: MutableList<GroupKDBX>) {
        group?.let {
            result.add(it)
            val groups = it.getChildGroups() as? List<GroupKDBX> ?: return@let
            for (childGroup in groups) {
                collectGroups(childGroup, result)
            }
        }
    }
}
