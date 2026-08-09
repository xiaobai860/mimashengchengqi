package com.lesspass.app.data

import android.content.Context
import android.content.SharedPreferences
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
        private const val KEY_HAS_DB = "has_db"
        private val HardwareKeyNoOp: (com.kunzisoft.keepass.hardware.HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val defaultDbFile: File
        get() = File(context.filesDir, "password_book.kdbx")

    private val dbFile: File
        get() = File(prefs(context).getString(KEY_DB_PATH, defaultDbFile.absolutePath) ?: defaultDbFile.absolutePath)

    private var database: DatabaseKDBX? = null
    private var isUnlocked = false
    private var savedMasterPassword: String? = null

    val unlocked: Boolean get() = isUnlocked
    val filePath: String get() = dbFile.absolutePath
    val exists: Boolean get() = dbFile.exists()
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
            setHasDatabase(true)
            saveDatabase()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 用密码打开数据库。password 为空时直接打开（无加密）。
     */
    fun openDatabase(password: String): Boolean {
        return try {
            if (!dbFile.exists()) return false

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
            true
        } catch (e: Exception) {
            e.printStackTrace()
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
            val db = database ?: return false

            // 验证旧密码（如果提供了且与当前密码不一致）
            if (oldPassword.isNotEmpty() && hasPassword && oldPassword != savedMasterPassword) {
                lock()
                if (!openDatabase(oldPassword)) return false
            }

            // 用新密码重新派生主密钥
            if (newPassword.isNotEmpty()) {
                val mc = MasterCredential(newPassword.toCharArray())
                db.deriveMasterKey(mc, HardwareKeyNoOp)
            } else {
                // 移除密码时，设置 masterKey 为全零（与 openDatabase 空密码行为一致）
                db.masterKey = ByteArray(32)
            }
            savedMasterPassword = newPassword
            setHasPassword(newPassword.isNotEmpty())

            // 保存数据库（内部会自动用新密钥重新加密整个文件）
            saveDatabase()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 保存数据库到文件
     */
    fun saveDatabase(): Boolean {
        return try {
            val db = database ?: return false
            FileOutputStream(dbFile).use { stream ->
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
            e.printStackTrace()
            false
        }
    }

    /**
     * 锁定数据库
     */
    fun lock() {
        database = null
        isUnlocked = false
        savedMasterPassword = null
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
