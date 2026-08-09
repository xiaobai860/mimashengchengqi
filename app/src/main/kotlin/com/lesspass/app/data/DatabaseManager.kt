package com.lesspass.app.data

import android.content.Context
import android.content.Intent
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
    val filePath: String get() = prefs(context).getString(KEY_DB_DISPLAY_PATH, null)
        ?: dbUri?.let { uri ->
            val fullPath = java.net.URLDecoder.decode(uri.path ?: return@let dbFile.absolutePath, "UTF-8")
            val pathSegments = fullPath.split("/").filter { it.isNotEmpty() }
            val treeIdx = pathSegments.indexOf("tree")
            if (treeIdx >= 0 && treeIdx + 1 < pathSegments.size) {
                // 过滤掉 DocumentProvider 的卷标识符（如 primary:）
                val docId = pathSegments.subList(treeIdx + 1, pathSegments.size)
                    .joinToString("/")
                    .removePrefix("primary:")
                "内部存储/$docId/"
            } else uri.toString()
        } ?: dbFile.absolutePath
    val exists: Boolean get() = dbFile.exists() || dbUri != null
    val masterPasswordValue: String? get() = savedMasterPassword
    val hasPassword: Boolean get() = prefs(context).getBoolean(KEY_HAS_PASSWORD, false)
    val hasDatabase: Boolean get() = prefs(context).getBoolean(KEY_HAS_DB, false)

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

    /** 从 URI 计算可读路径 */
    private fun computeDisplayPath(uri: Uri): String {
        val fullPath = java.net.URLDecoder.decode(uri.path ?: return uri.toString(), "UTF-8")
        val segments = fullPath.split("/").filter { it.isNotEmpty() }
        val treeIdx = segments.indexOf("tree")
        return if (treeIdx >= 0 && treeIdx + 1 < segments.size) {
            "内部存储/${segments.subList(treeIdx + 1, segments.size).joinToString("/")}/"
        } else uri.toString()
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

            database = db
            isUnlocked = true
            savedMasterPassword = if (password.isNotEmpty()) password else null
            setHasPassword(password.isNotEmpty())
            if (!password.isNotEmpty()) {
                prefs(context).edit().putBoolean(KEY_AUTO_UNLOCK, true).apply()
            } else {
                prefs(context).edit().remove(KEY_AUTO_UNLOCK).apply()
            }
            val saved = saveDatabase()
            setHasDatabase(saved)
            Log.d("MimaDB", "createDatabase: saved=$saved")
            true
        } catch (e: Exception) {
            Log.e("MimaDB", "createDatabase failed", e)
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
            Log.e("MimaDB", "saveDatabase failed", e)
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

    /** 用文件夹 URI 迁移数据库（从文件夹选择器选取目标目录） */
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
            val newFile = parentDir.createFile(mimeType, "password_book.kdbx")
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
            // 持久化：同时更新 dbUri 和 currentDbFile
            prefs(context).edit()
                .putString(KEY_DB_URI, uri.toString())
                .putString(KEY_DB_EXTERNAL_URI, uri.toString())
                .putString(KEY_DB_DISPLAY_PATH, computeDisplayPath(uri))
                .putString(KEY_CURRENT_DB_FILE, uri.path ?: uri.toString())
                .apply()
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
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
                        hasPassword = false // 需要实际打开才能判断，默认 false
                    )
                } catch (e: Exception) {
                    Log.e("MimaDB", "listKdbxFiles error for ${file.name}", e)
                    null
                }
            }?.sortedBy { it.name } ?: emptyList()
    }

    data class KdbxFileInfo(
        val name: String,
        val path: String,
        val uri: Uri,
        val hasPassword: Boolean,
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
            // 删除所有 SharedPreferences
            prefs(context).edit().clear().apply()
            // 删除缓存目录（不包括密码本文件）
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.name != "kdbx_export_*.kdbx") file.delete()
            }
            // 删除应用文件目录中的临时文件（保留 password_book.kdbx）
            context.filesDir?.listFiles()?.forEach { file ->
                if (file.name != "password_book.kdbx") file.delete()
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
