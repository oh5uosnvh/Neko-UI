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

    private fun repair(groupId: Long, uri: Uri) {
        val context = fragment.context ?: return
        fragment.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val sourceText = context.contentResolver.openInputStream(uri)?.use { it.readUtf8Limited() }
                        ?: return@withContext Result.NoMatch
                    val source = RawUpdater.parseRaw(sourceText).orEmpty()
                    coroutineContext.ensureActive()
                    val candidates = SagerDatabase.proxyDao.getByGroup(groupId)
                        .mapNotNull { old ->
                            val bean = old.vmessBean ?: return@mapNotNull null
                            if (!LegacyVmessWsRecovery.eligible(bean)) return@mapNotNull null
                            val repaired = LegacyVmessWsRecovery.replacement(bean, source)
                                ?: return@mapNotNull null
                            Candidate(old, repaired)
                        }
                    if (candidates.isEmpty()) Result.NoMatch else Result.Candidates(candidates)
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
                is Result.Candidates -> confirm(groupId, result.items)
            }
        }
    }

    private fun confirm(groupId: Long, candidates: List<Candidate>) {
        val context = fragment.context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.vmess_recovery_title)
            .setMessage(context.getString(R.string.vmess_recovery_confirm, candidates.size))
            .setPositiveButton(R.string.yes) { _, _ -> commit(groupId, candidates) }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun commit(groupId: Long, candidates: List<Candidate>) {
        fragment.lifecycleScope.launch {
            val repaired = try {
                withContext(Dispatchers.IO) {
                    var count = 0
                    val job = currentCoroutineContext()[Job]
                    SagerDatabase.instance.runInTransaction {
                        for (candidate in candidates) {
                            job?.ensureActive()
                            if (LegacyVmessWsRecovery.commitIfUnchanged(
                                    SagerDatabase.instance, candidate.old.id, groupId,
                                    candidate.snapshot, candidate.repaired
                                )) count++
                        }
                    }
                    count
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1
            }
            if (!fragment.isAdded) return@launch
            if (repaired < 0) {
                show(R.string.vmess_recovery_error)
            } else {
                GroupManager.postReload(groupId)
                show(fragment.resources.getString(R.string.vmess_recovery_done, repaired, candidates.size))
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

    private data class Candidate(
        val old: ProxyEntity,
        val repaired: io.nekohasekai.sagernet.fmt.v2ray.VMessBean,
        val snapshot: ByteArray = KryoConverters.serialize(old.vmessBean)
    )

    private sealed interface Result {
        data object NoMatch : Result
        data object Error : Result
        data class Candidates(val items: List<Candidate>) : Result
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
