package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private lateinit var binding: LayoutBackupBinding
    private lateinit var backupData: ByteArray
    private var isWebDAVBackup = false
    private var isBackupInProgress = false
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        // 如果正在进行恢复操作，取消它
        if (isRestoreInProgress) {
            restoreJob?.cancel()
            restoreJob = null
            isRestoreInProgress = false
            MessageStore.showMessage(requireActivity(), R.string.restore_cancelled)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, "nekobox_backup_${Date().toLocaleString()}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeBytes(backupData)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        setupWebDAV(binding)
    }

    private fun setupWebDAV(binding: LayoutBackupBinding) {
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }
        
        binding.backupToWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            backupToWebDAV()
        }
        
        binding.restoreFromWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            restoreFromWebDAV()
        }
    }

    private fun backupToWebDAV() {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        val activity = requireActivity()
        runOnDefaultDispatcher {
            try {
                isWebDAVBackup = true
                val backupData = doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true   // 备份设置
                )
                isWebDAVBackup = false
                
                val client = OkHttpClient()

                // 规范化 URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                // 使用英文格式的时间戳作为文件名，修改后缀为 .zip
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "nekobox_backup_${version}_$timestamp.zip"

                // 确保 baseUrl 是有效的 URL
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                // 使用 HttpUrl 构建路径，避免 # 等特殊字符被当作 fragment
                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // 先检查目录是否存在
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // 目录存在
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // 如果需要，创建目录
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // 上传文件时使用正确的 Content-Type
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody("application/zip".toMediaType()))
                    .apply {
                        header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.webdav_backup_success)
                }
            } catch (e: Exception) {
                isWebDAVBackup = false  // 确保发生异常时也重置标志
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }
                
                onMainDispatcher {
                    MessageStore.showMessage(activity, errorMessage)
                }
            } finally {
                isBackupInProgress = false
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = requireActivity()
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = OkHttpClient()
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // 先列出目录内容找到最新的备份文件
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "1")
                    .build()

                // 获取最新的备份文件名
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")
                    
                    val patterns = listOf(
                        """<D:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</D:href>""".toRegex(),
                        """<d:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</d:href>""".toRegex(),
                        """<href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</href>""".toRegex()
                    )
                    
                    val backupFiles = mutableListOf<String>()
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex()
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }
                    
                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.bytes() ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // 根据文件类型处理内容
                val backupContent = if (latestBackup.endsWith(".zip")) {
                    // ZIP 文件处理
                    ZipInputStream(content.inputStream()).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    // JSON 文件处理
                    content.toString(Charsets.UTF_8)
                }

                // 解析并导入备份数据
                val json = JSONObject(backupContent)
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                        return@onMainDispatcher
                    }

                    val import = LayoutImportBinding.inflate(layoutInflater)
                    if (!json.has("profiles")) {
                        import.backupConfigurations.isVisible = false
                        import.appendBackupConfigurations.isVisible = false
                    }
                    if (!json.has("rules")) {
                        import.backupRules.isVisible = false
                    }
                    if (!json.has("settings")) {
                        import.backupSettings.isVisible = false
                    }

                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                        .setView(import.root)
                        .setPositiveButton(R.string.backup_import) { _, _ ->
                            SagerNet.stopService()

                            val binding = LayoutProgressBinding.inflate(layoutInflater)
                            binding.content.text = getString(R.string.backup_importing)
                            val dialog = AlertDialog.Builder(requireContext())
                                .setView(binding.root)
                                .setCancelable(false)
                                .show()
                            runOnDefaultDispatcher {
                                runCatching {
                                    // 再次检查是否已被取消
                                    if (!isAdded) {
                                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                                        return@runOnDefaultDispatcher
                                    }
                                    finishImport(
                                        json,
                                        import.backupConfigurations.isChecked,
                                        import.backupRules.isChecked,
                                        import.backupSettings.isChecked,
                                        import.backupConfigurations.isChecked &&
                                            import.appendBackupConfigurations.isChecked
                                    )
                                    ProcessPhoenix.triggerRebirth(
                                        activity, Intent(activity, MainActivity::class.java)
                                    )
                                }.onFailure {
                                    Logs.w(it)
                                    onMainDispatcher {
                                        MessageStore.showMessage(activity, it.readableMessage)
                                    }
                                }

                                onMainDispatcher {
                                    dialog.dismiss()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean
    ): ByteArray {
        val out = JSONObject().apply {
            // Version 2 includes tag groups and memberships. Root groups and
            // profiles keep their original IDs so classification survives restore.
            put("version", 2)
            if (profile) {
                // Read all profile/group/tag tables from one Room snapshot. This
                // prevents an update racing the backup from producing dangling IDs.
                SagerDatabase.instance.runInTransaction {
                    put("profiles", JSONArray().apply {
                        SagerDatabase.proxyDao.getAll().forEach { put(it.toBase64Str()) }
                    })
                    put("groups", JSONArray().apply {
                        SagerDatabase.groupDao.allGroups().forEach { put(it.toBase64Str()) }
                    })
                    val tags = SagerDatabase.instance.tagGroupDao().all()
                    put("tag_groups", JSONArray().apply {
                        tags.forEach { tag ->
                            put(JSONObject().apply {
                                put("id", tag.id)
                                put("name", tag.name)
                                put("userOrder", tag.userOrder)
                            })
                        }
                    })
                    put("tag_memberships", JSONArray().apply {
                        tags.forEach { tag ->
                            SagerDatabase.instance.tagMembershipDao().members(tag.id).forEach { groupId ->
                                put(JSONObject().apply {
                                    put("tagId", tag.id)
                                    put("groupId", groupId)
                                })
                            }
                        }
                    })
                }
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }

        val jsonContent = out.toStringPretty()
        return if (isWebDAVBackup) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    
                    val entry = ZipEntry("nekobox_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    }
                    
                    // 写入数据
                    zos.putNextEntry(entry)
                    val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                    zos.write(bytes)
                    zos.closeEntry()
                    
                    // 确保所有数据都被写入和压缩
                    zos.finish()
                }
                bos.toByteArray()
            }
        } else {
            // 本地导出和分享功能使用 JSON 格式
            jsonContent.toByteArray()
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val content = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }

            val json = JSONObject(content)
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                    import.appendBackupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                import.appendBackupConfigurations.setOnCheckedChangeListener { _, checked ->
                    import.backupImportSummary.setText(
                        if (checked) R.string.backup_import_append_summary
                        else R.string.backup_import_summary
                    )
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    import.backupConfigurations.isChecked,
                                    import.backupRules.isChecked,
                                    import.backupSettings.isChecked,
                                    import.backupConfigurations.isChecked &&
                                        import.appendBackupConfigurations.isChecked
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    /**
     * Restores only entries that can be decoded and validated. One malformed
     * custom/unknown bean must not abort the entire backup. When append is true,
     * backup groups are added without resetting existing groups; old IDs are
     * remapped so profiles and tag memberships remain valid.
     */
    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean,
        appendConfigurations: Boolean = false,
    ) {
        var skipped = 0
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                try {
                    val data = Util.b64Decode(jsonProfiles.getString(i))
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(data, 0, data.size)
                        parcel.setDataPosition(0)
                        profiles += ProxyEntity.CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                } catch (error: Throwable) {
                    skipped++
                    Logs.w("Skipping backup profile $i", error)
                }
            }
            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until jsonGroups.length()) {
                try {
                    val data = Util.b64Decode(jsonGroups.getString(i))
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(data, 0, data.size)
                        parcel.setDataPosition(0)
                        groups += ProxyGroup.CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                } catch (error: Throwable) {
                    skipped++
                    Logs.w("Skipping backup group $i", error)
                }
            }
            val groupIds = groups.map { it.id }.toSet()
            val validGroups = groups.distinctBy { it.id }
            val acceptedGroups = validGroups.mapNotNull { group ->
                runCatching { group.applyDefaultValues(); group }.onFailure {
                    skipped++
                    Logs.w("Skipping invalid backup group ${group.id}", it)
                }.getOrNull()
            }.associateBy { it.id }
            val acceptedProfiles = profiles.filter { profile ->
                if (profile.groupId != 0L && profile.groupId !in acceptedGroups) return@filter false
                runCatching { profile.requireBean(); profile.displayName() }.onFailure {
                    skipped++
                    Logs.w("Skipping unsupported backup profile", it)
                }.isSuccess
            }
            val tags = mutableListOf<TagGroup>()
            if (content.has("tag_groups")) {
                val jsonTags = content.getJSONArray("tag_groups")
                for (i in 0 until jsonTags.length()) {
                    try {
                        val item = jsonTags.getJSONObject(i)
                        val name = item.getString("name").trim()
                        if (name.isEmpty()) throw IllegalArgumentException("empty tag name")
                        tags += TagGroup(item.getLong("id"), name, item.optLong("userOrder", i + 1L))
                    } catch (error: Throwable) {
                        skipped++
                        Logs.w("Skipping backup tag $i", error)
                    }
                }
            }
            val memberships = mutableListOf<TagMembership>()
            if (content.has("tag_memberships")) {
                val jsonMemberships = content.getJSONArray("tag_memberships")
                for (i in 0 until jsonMemberships.length()) {
                    try {
                        val item = jsonMemberships.getJSONObject(i)
                        memberships += TagMembership(item.getLong("tagId"), item.getLong("groupId"))
                    } catch (error: Throwable) {
                        skipped++
                        Logs.w("Skipping backup membership $i", error)
                    }
                }
            }
            val restoreExistingTagLinks = !content.has("tag_groups") && !content.has("tag_memberships")
            val existingTagLinks = if (restoreExistingTagLinks) {
                SagerDatabase.instance.tagGroupDao().all().flatMap { tag ->
                    SagerDatabase.instance.tagMembershipDao().members(tag.id).map { groupId ->
                        TagMembership(tag.id, groupId)
                    }
                }
            } else emptyList()

            SagerDatabase.instance.runInTransaction {
                val groupIdMap = LinkedHashMap<Long, Long>()
                if (appendConfigurations) {
                    acceptedGroups.values.forEach { source ->
                        if (source.id <= 0L) return@forEach
                        val copy = source.copy(id = 0L)
                        groupIdMap[source.id] = SagerDatabase.groupDao.createGroup(copy)
                    }
                } else {
                    if (acceptedGroups.isEmpty()) {
                        Logs.w("Backup contains no decodable configuration groups; preserving current configuration")
                    } else {
                        SagerDatabase.instance.tagMembershipDao().reset()
                        SagerDatabase.proxyDao.reset()
                        SagerDatabase.groupDao.reset()
                        SagerDatabase.groupDao.insert(acceptedGroups.values.toList())
                        acceptedGroups.keys.forEach { groupIdMap[it] = it }
                    }
                }

                val importedProfiles = acceptedProfiles.mapNotNull { source ->
                    val newGroupId = groupIdMap[source.groupId] ?: return@mapNotNull null
                    runCatching {
                        val bean = source.requireBean().applyDefaultValues()
                        source.copy(id = 0L, groupId = newGroupId).apply {
                            // Rebuild the one active bean explicitly. This removes
                            // stale/null sibling blobs from old or magic backups,
                            // which otherwise fail Room bindBlob at a later index.
                            putBean(bean)
                            uuid = source.uuid.orEmpty()
                            error = null
                        }
                    }.onFailure {
                        skipped++
                        Logs.w("Skipping unsupported backup profile ${source.id}", it)
                    }.getOrNull()
                }
                if (groupIdMap.isNotEmpty() && importedProfiles.isNotEmpty()) {
                    SagerDatabase.proxyDao.insert(importedProfiles)
                }

                if (content.has("tag_groups") || content.has("tag_memberships")) {
                    val tagIdMap = LinkedHashMap<Long, Long>()
                    if (!appendConfigurations) SagerDatabase.instance.tagGroupDao().reset()
                    val existingNames = SagerDatabase.instance.tagGroupDao().all().map { it.name }.toMutableSet()
                    tags.forEach { source ->
                        var name = source.name
                        var suffix = 2
                        while (!existingNames.add(name)) {
                            name = "${source.name} ($suffix)"
                            suffix++
                        }
                        val newId = SagerDatabase.instance.tagGroupDao().insert(
                            source.copy(id = 0L, name = name)
                        )
                        tagIdMap[source.id] = newId
                    }
                    val links = memberships.mapNotNull { link ->
                        val tagId = tagIdMap[link.tagId] ?: return@mapNotNull null
                        val groupId = groupIdMap[link.groupId] ?: return@mapNotNull null
                        TagMembership(tagId, groupId)
                    }
                    if (links.isNotEmpty()) SagerDatabase.instance.tagMembershipDao().insertAll(links)
                } else if (!appendConfigurations && existingTagLinks.isNotEmpty()) {
                    val validGroups = groupIdMap.values.toSet()
                    val validLinks = existingTagLinks.filter { it.groupId in validGroups }
                    if (validLinks.isNotEmpty()) SagerDatabase.instance.tagMembershipDao().insertAll(validLinks)
                }
            }
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                try {
                    val data = Util.b64Decode(jsonRules.getString(i))
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(data, 0, data.size)
                        parcel.setDataPosition(0)
                        rules += ParcelizeBridge.createRule(parcel).normalizedForImport()
                    } finally { parcel.recycle() }
                } catch (error: Throwable) {
                    skipped++
                    Logs.w("Skipping backup rule $i", error)
                }
            }
            // If every entry is unknown/corrupt, keep the current rules instead
            // of turning a partial import into a destructive clear.
            if (rules.isNotEmpty()) {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(rules)
            }
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                try {
                    val data = Util.b64Decode(jsonSettings.getString(i))
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(data, 0, data.size)
                        parcel.setDataPosition(0)
                        settings += KeyValuePair.CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                } catch (error: Throwable) {
                    skipped++
                    Logs.w("Skipping backup setting $i", error)
                }
            }
            // Preserve current settings when the backup contains no decodable
            // entries; unsupported custom keys must not wipe the database.
            if (settings.isNotEmpty()) {
                PublicDatabase.kvPairDao.reset()
                PublicDatabase.kvPairDao.insert(settings)
            }
        }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }

    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
