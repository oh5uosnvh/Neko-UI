package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/** Shared node-list export, instantiated as a Fragment field before onCreate. */
internal class GroupProfileExport(private val fragment: Fragment) {
    private val pending = PendingGroupExport()

    // Register once at Fragment creation; never from the popup or onViewCreated.
    private val createDocument =
        fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val groupId = pending.consume() // Cancellation must also forget the target.
            if (uri != null && groupId != null) {
                export {
                    withContext(Dispatchers.IO) {
                        val links = readLinks(groupId)
                        val output = contentResolver.openOutputStream(uri)
                            ?: error("Unable to open export document")
                        output.bufferedWriter().use { it.write(links) }
                    }
                }
            }
        }

    fun restoreState(savedInstanceState: Bundle?) = pending.restoreState(savedInstanceState)

    fun saveState(outState: Bundle) = pending.saveState(outState)

    fun toFile(groupId: Long, groupName: String) {
        if (!pending.begin(groupId)) return
        try {
            createDocument.launch("profiles_${groupName}.txt")
        } catch (_: Exception) {
            pending.consume()
            showResult(R.string.action_export_err)
        }
    }

    fun toClipboard(groupId: Long) {
        export {
            val links = withContext(Dispatchers.IO) { readLinks(groupId) }
            // Keep clipboard access on Main. Do not log platform exceptions: they
            // may contain a node URL or subscription credentials.
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(null, links))
        }
    }

    private fun export(action: suspend Context.() -> Unit) {
        val context = fragment.context?.applicationContext ?: return
        fragment.lifecycleScope.launch {
            val message = try {
                action(context)
                R.string.action_export_msg
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Deliberately use a fixed, short message, never exception text.
                R.string.action_export_err
            }
            showResult(message)
        }
    }

    private fun showResult(message: Int) {
        val view = fragment.view ?: return
        if (!fragment.isAdded) return
        val activity = fragment.activity as? MainActivity
        if (fragment is ConfigurationFragment && activity != null) {
            activity.snackbar(message).setDuration(Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    internal companion object {
        /** Null means the requested group was deleted, not an empty group. */
        fun readLinks(
            groupId: Long,
            loadProfiles: (Long) -> List<ProxyEntity>? = ::loadGroup,
        ): String {
            require(groupId > 0L) { "Invalid export group" }
            val profiles = loadProfiles(groupId) ?: error("Export group no longer exists")
            // Preserve the original GroupFragment format and DAO order, including
            // empty links. This is neither exportConfig() nor a subscription link.
            return try {
                profiles.joinToString("\n") { it.toStdLink(compact = true) }
            } catch (error: Exception) {
                throw IllegalStateException("Unable to serialize export group", error)
            }
        }

        private fun loadGroup(groupId: Long): List<ProxyEntity>? {
            val database = SagerDatabase.instance
            return database.runInTransaction(Callable {
                if (database.groupDao().getById(groupId) == null) null
                else database.proxyDao().getByGroup(groupId)
            })
        }
    }
}
