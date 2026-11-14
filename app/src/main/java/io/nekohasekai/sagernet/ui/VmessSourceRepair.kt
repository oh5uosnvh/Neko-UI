package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.group.LegacyVmessWsRecovery
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Explicit, local-only repair of empty legacy VMess WebSocket paths. */
internal class VmessSourceRepair(private val fragment: Fragment) {
    private val pending = PendingGroupExport("pending_vmess_source_repair_group_id")
    private val openDocument = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val groupId = pending.consume()
        if (uri != null && groupId != null) repair(groupId, uri)
    }

    fun restoreState(savedInstanceState: Bundle?) = pending.restoreState(savedInstanceState)
    fun saveState(outState: Bundle) = pending.saveState(outState)

    fun open(groupId: Long) {
        if (!pending.begin(groupId)) return
        try {
            openDocument.launch(arrayOf("application/json", "application/yaml", "text/*", "*/*"))
        } catch (_: Exception) {
            pending.consume()
            show(R.string.vmess_recovery_error)
        }
    }

    /** Normal YAML import must not silently add good duplicates while the selected old ID stays broken. */
    suspend fun chooseImportRepair(count: Int): Int = withContext(Dispatchers.Main) {
        if (!fragment.isAdded) return@withContext 0
        if (io.nekohasekai.sagernet.database.DataStore.runningTest) {
            show(R.string.vmess_recovery_wait_test)
            return@withContext 0
        }
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val context = fragment.requireContext()
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vmess_recovery_title)
                .setMessage(context.getString(R.string.vmess_recovery_import_choice, count))
                .setPositiveButton(R.string.vmess_recovery_repair_existing) { _, _ ->
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(1))
                }
                .setNeutralButton(R.string.vmess_recovery_append_instead) { _, _ ->
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(2))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(0))
                }.create()
            dialog.setOnCancelListener { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(0)) }
            dialog.setOnDismissListener { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(0)) }
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { dialog.dismiss() } }
            dialog.show()
        }
    }

    private fun repair(groupId: Long, uri: Uri) {
        val context = fragment.context ?: return
        fragment.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val sourceText = context.contentResolver.openInputStream(uri)?.use { it.readUtf8Limited() }
                        ?: return@withContext Result.NoMatch
                    val source = RawUpdater.parseRaw(sourceText).orEmpty()
                    coroutineContext.ensureActive()
                    val plan = io.nekohasekai.sagernet.group.VmessSourceRepairPlan.prepare(groupId,
                        SagerDatabase.proxyDao.getByGroup(groupId), source)
                    if (plan.candidates.isEmpty()) Result.NoMatch else Result.Candidates(plan)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.Error
            }
            if (!fragment.isAdded) return@launch
            when (result) {
                Result.Error -> show(R.string.vmess_recovery_error)
                Result.NoMatch -> show(R.string.vmess_recovery_no_match)
                is Result.Candidates -> confirm(result.plan)
            }
        }
    }

    private fun confirm(plan: io.nekohasekai.sagernet.group.VmessSourceRepairPlan) {
        val context = fragment.context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.vmess_recovery_title)
            .setMessage(context.getString(R.string.vmess_recovery_confirm, plan.candidates.size))
            .setPositiveButton(R.string.yes) { _, _ -> commit(plan) }
            .setNegativeButton(R.string.no, null).show()
    }

    private fun commit(plan: io.nekohasekai.sagernet.group.VmessSourceRepairPlan) {
        fragment.lifecycleScope.launch {
            if (io.nekohasekai.sagernet.database.DataStore.runningTest) {
                show(R.string.vmess_recovery_wait_test)
                return@launch
            }
            val repairedIds = try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    plan.commitIds(SagerDatabase.instance) { job?.ensureActive() }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
            if (!fragment.isAdded) return@launch
            if (repairedIds == null) show(R.string.vmess_recovery_error)
            else {
                (fragment as? ConfigurationFragment)?.invalidateRepairedLatencyResults(repairedIds)
                GroupManager.postReload(plan.groupId)
                if (io.nekohasekai.sagernet.database.DataStore.serviceState.canStop &&
                    io.nekohasekai.sagernet.database.DataStore.currentProfile in repairedIds)
                    io.nekohasekai.sagernet.SagerNet.reloadService()
                show(fragment.resources.getString(R.string.vmess_recovery_done, repairedIds.size, plan.candidates.size))
            }
        }
    }

    private fun show(message: Int) {
        val host = fragment as? ConfigurationFragment
        if (host != null) host.snackbar(message).show()
        else fragment.view?.let { com.google.android.material.snackbar.Snackbar.make(it, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() }
    }

    private fun show(message: String) {
        val host = fragment as? ConfigurationFragment
        if (host != null) host.snackbar(message).show()
        else fragment.view?.let { com.google.android.material.snackbar.Snackbar.make(it, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() }
    }

    private sealed interface Result {
        data object NoMatch : Result
        data object Error : Result
        data class Candidates(val plan: io.nekohasekai.sagernet.group.VmessSourceRepairPlan) : Result
    }

    private fun java.io.InputStream.readUtf8Limited(): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_SOURCE_BYTES) error("source too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 4 * 1024 * 1024
    }
}
