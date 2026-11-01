package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.assets.KaringResourceManager
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION || index < 3) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index < 3 || index >= adapter.assets.size) return
                val file = adapter.assets[index]
                adapter.remove(index)
                undoManager.remove(index to file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')

            if (!fileName.endsWith(".db") || fileName != File(fileName).name || fileName == ".db") {
                alert(getString(R.string.route_not_asset, fileName)).show()
                return@registerForActivityResult
            }
            val filesDir = getExternalFilesDir(null) ?: filesDir

            runOnDefaultDispatcher {
                runCatching {
                    val outFile = File(filesDir, fileName)
                    require(!outFile.exists()) { getString(R.string.karing_asset_exists, fileName) }
                    val temp = File.createTempFile("asset-", ".part", filesDir)
                    try {
                        val input = contentResolver.openInputStream(file) ?: error("Cannot open selected file")
                        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                        // createNewFile prevents a simultaneous import from replacing an existing asset.
                        check(outFile.createNewFile()) { getString(R.string.karing_asset_exists, fileName) }
                        try {
                            check(temp.renameTo(outFile)) { "Cannot install selected file" }
                        } catch (e: Exception) {
                            outFile.delete()
                            throw e
                        }
                        File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").writeText("Custom")
                    } finally {
                        temp.delete()
                    }
                    adapter.reloadAssets()
                }.onFailure { error -> onMainDispatcher { alert(error.readableMessage).tryToShow() } }
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            val refreshed = listOf(File(filesDir, "geoip.db"), File(filesDir, "geosite.db"),
                File(filesDir, "karing-acl-card")) + files.orEmpty().sortedBy { it.name }
            layout.refreshLayout.post {
                assets.clear()
                assets.addAll(refreshed)
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file
            binding.root.setOnClickListener(null)
            val family = when (file.name) {
                "geoip.db" -> KaringRuleFamily.GEOIP
                "geosite.db" -> KaringRuleFamily.GEOSITE
                "karing-acl-card" -> KaringRuleFamily.ACL
                else -> null
            }
            binding.edit.isInvisible = family == null
            binding.edit.setOnClickListener {
                if (family != null && !supportFragmentManager.isStateSaved) {
                    KaringResourceSettingsDialog.newInstance(family).show(supportFragmentManager, "resource-settings")
                }
            }
            binding.subscriptionUpdateProgress.isInvisible = true
            if (file.name == "karing-acl-card") {
                binding.assetName.setText(R.string.karing_acl_resources)
                binding.assetStatus.setText(R.string.karing_acl_loading)
                binding.rulesUpdate.isInvisible = true
                binding.root.setOnClickListener { showAclResources() }
                runOnDefaultDispatcher {
                    val counts = runCatching {
                        KaringResourceManager(applicationContext).list(KaringRuleFamily.ACL).let { list ->
                            list.count { it.bundled } to list.count { it.valid }
                        }
                    }
                    onMainDispatcher {
                        if (this@AssetHolder.file != file) return@onMainDispatcher
                        binding.assetStatus.text = counts.fold(
                            { (bundled, valid) -> getString(R.string.karing_acl_status, bundled, valid) },
                            { it.readableMessage }
                        )
                    }
                }
                return
            }

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage)
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateAsset(file, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private fun showAclResources() {
        runOnDefaultDispatcher {
            runCatching {
                val manager = KaringResourceManager(applicationContext)
                manager to manager.list(KaringRuleFamily.ACL)
            }.onSuccess { (manager, entries) ->
                onMainDispatcher {
                    if (isFinishing || isDestroyed) return@onMainDispatcher
                    if (entries.isEmpty()) {
                        alert(getString(R.string.karing_acl_empty)).show()
                        return@onMainDispatcher
                    }
                    val labels = entries.map { entry ->
                        val status = when {
                            entry.valid -> getString(R.string.karing_acl_ready)
                            entry.bundled -> getString(R.string.karing_acl_bundled)
                            else -> getString(R.string.karing_acl_unavailable)
                        }
                        "${entry.code} · $status"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this@AssetsActivity).setTitle(R.string.karing_acl_resources)
                        .setItems(labels) { _, which ->
                            val entry = entries[which]
                            if (!entry.bundled && !entry.valid) {
                                alert(getString(R.string.karing_acl_unavailable)).show()
                            } else runOnDefaultDispatcher {
                                runCatching { manager.ensure(KaringRuleFamily.ACL, entry.code) }
                                    .onSuccess {
                                        onMainDispatcher {
                                            snackbar(getString(R.string.karing_acl_installed, entry.code)).show()
                                            adapter.reloadAssets()
                                        }
                                    }.onFailure { error ->
                                        onMainDispatcher { alert(error.readableMessage).tryToShow() }
                                    }
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.onFailure { error -> onMainDispatcher { alert(error.readableMessage).tryToShow() } }
        }
    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo"
        ),
    )

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        if (DataStore.rulesProvider == 4){
            return updateCustomAsset(file, versionFile)
        }
        val fileName = file.name
        val repo = rulesProviders[DataStore.rulesProvider].repoByFileName[fileName]

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedInboundUser,
                DataStore.mixedInboundPass
            )
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name")

            if (tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val cacheFile = File(file.parentFile, fileName + ".tmp")
            cacheFile.parentFile?.mkdirs()

            response.writeTo(cacheFile.canonicalPath)

            if (fileName.endsWith(".xz")) {
                Libcore.unxz(cacheFile.absolutePath, file.absolutePath)
                cacheFile.delete()
            } else {
                cacheFile.renameTo(file)
            }

            versionFile.writeText(tagName)

            adapter.reloadAssets()

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    suspend fun updateCustomAsset(file: File, versionFile: File) {
        val fileName = file.name
        val url: String = if (fileName == "geoip.db") {
            DataStore.rulesGeoipUrl
        } else if (fileName == "geosite.db") {
            DataStore.rulesGeositeUrl
        } else {
            return
        }
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedInboundUser,
                DataStore.mixedInboundPass
            )
        }
        try {
            val response = client.newRequest().apply {
                setURL(url)
            }.execute()
            val cacheFile = File(file.parentFile, fileName + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            cacheFile.renameTo(file)

            val currentDate = java.text.SimpleDateFormat("yyyyMMdd").format(java.util.Date())
            versionFile.writeText(currentDate)

            adapter.reloadAssets()
            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
            // if (versionFile.isFile) {
            //     versionFile.delete()
            // }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}
