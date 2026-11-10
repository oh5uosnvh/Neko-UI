package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.database.TagGroupStore
import io.nekohasekai.sagernet.database.PendingGroupDeletes
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secondary tag list. All synchronous store access, including counts, runs on IO. */
class TagGroupFragment : ToolbarFragment(R.layout.layout_tag_group) {
    private var list: RecyclerView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var adapter: TagAdapter? = null
    private var touchHelper: ItemTouchHelper? = null
    private var empty: TextView? = null
    private var savedScroll: Parcelable? = null
    private var pendingScrollRestore = false
    private var refreshJob: Job? = null
    private var operationJob: Job? = null
    private var undoSnackbar: Snackbar? = null
    private var confirmation: androidx.appcompat.app.AlertDialog? = null
    private var viewGeneration = 0
    private var dragging = false
    private var bodyGesture = true
    private var orderChanged = false
    private var dirty = false
    private var busy = false
    private var ignoreClickUntil = 0L
    private var backCallback: OnBackPressedCallback? = null

    companion object {
        const val ARG_TAG_ID = "tag_id"
        const val ARG_TAG_EDIT = "tag_edit"
        private const val STATE_SCROLL = "tag_group_scroll"
    }

    private data class Row(val tag: TagGroup, val count: Int)
    private data class PendingUndo(val name: String, val restore: () -> Unit)
    private var pendingUndo: PendingUndo? = null

    override fun resetScrollState() = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity
        viewGeneration++
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        TagGroupNavigation.remember(host, this)
        toolbar.setTitle(R.string.tag_title)
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.navigationContentDescription = getString(R.string.tag_back)
        toolbar.setNavigationOnClickListener { returnToGroups() }
        toolbar.menu.add(Menu.NONE, R.id.tag_add, Menu.NONE, R.string.tag_add).apply {
            setIcon(R.drawable.ic_topbar_add)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // Discoverable overflow action; card edit still enters member edit directly.
        toolbar.menu.add(Menu.NONE, R.id.tag_rename, Menu.NONE, R.string.tag_rename)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.tag_add -> { showNameDialog(); true }
                R.id.tag_rename -> { chooseRename(); true }
                else -> false
            }
        }
        ToolbarIconPalette.apply(toolbar, requireContext())
        backCallback = object : OnBackPressedCallback(!isHidden) {
            override fun handleOnBackPressed() = returnToGroups()
        }.also { host.onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        list = view.findViewById<RecyclerView>(R.id.tag_list)
        empty = view.findViewById(R.id.tag_empty)
        layoutManager = LinearLayoutManager(requireContext()).also { list?.layoutManager = it }
        savedScroll = savedInstanceState?.getParcelable(STATE_SCROLL)
            ?: savedScroll ?: arguments?.getParcelable(TagGroupNavigation.STATE_SCROLL)
        pendingScrollRestore = savedScroll != null
        adapter = TagAdapter().also { list?.adapter = it }
        installDrag()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val changes = withContext(Dispatchers.IO) { TagGroupStore.changes }
                combine(changes, PendingGroupDeletes.changes) { tagsVersion, pendingVersion ->
                    tagsVersion to pendingVersion
                }.collectLatest {
                    if (dragging || busy) dirty = true else refresh()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        backCallback?.isEnabled = !isHidden
        requestRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        backCallback?.isEnabled = !hidden
        if (hidden) {
            captureScroll()
            confirmation?.dismiss()
            confirmation = null
            dismissUndo()
        } else {
            requestRefresh()
        }
    }

    override fun onBackPressed(): Boolean { returnToGroups(); return true }

    private fun returnToGroups() {
        captureScroll()
        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_group, forward = false)
    }

    private fun captureScroll() {
        savedScroll = layoutManager?.onSaveInstanceState() ?: savedScroll
        (activity as? MainActivity)?.let { TagGroupNavigation.rememberScroll(it, this, savedScroll) }
    }

    private suspend fun refresh() {
        val token = viewGeneration
        try {
            val rows = withContext(Dispatchers.IO) {
                TagGroupStore.tags().map { tag ->
                    Row(tag, TagGroupStore.members(tag.id).filterNot(PendingGroupDeletes::contains).size)
                }
            }
            if (token != viewGeneration || view == null || dragging || busy) {
                dirty = true
                return
            }
            dirty = false
            val state = if (pendingScrollRestore) savedScroll else layoutManager?.onSaveInstanceState()
            adapter?.replace(rows)
            empty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            toolbar.menu.findItem(R.id.tag_rename)?.isEnabled = rows.isNotEmpty()
            // PREVENT_WHEN_EMPTY keeps initial async loading from consuming restored offset.
            if (rows.isNotEmpty() && state != null) {
                layoutManager?.onRestoreInstanceState(state)
                pendingScrollRestore = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (token == viewGeneration) showMessage(R.string.tag_operation_failed)
        }
    }

    private fun requestRefresh() {
        if (view == null || dragging || busy) { dirty = true; return }
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch { refresh() }
    }

    internal fun onNameSaved(renamed: Boolean) {
        requestRefresh()
        // A create (even with the deleted name) must not replace/restart an active undo bar.
        if (pendingUndo == null) showMessage(if (renamed) R.string.tag_renamed else R.string.tag_created)
    }

    private fun showNameDialog(tag: TagGroup? = null) {
        if (busy || dragging || childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag(TagGroupNameDialogFragment.DIALOG_TAG) != null) return
        TagGroupNameDialogFragment.newInstance(tag?.id, tag?.name.orEmpty())
            .show(childFragmentManager, TagGroupNameDialogFragment.DIALOG_TAG)
    }

    private fun chooseRename() {
        if (!canClick()) return
        val tags = adapter?.rows?.map { it.tag }.orEmpty()
        if (tags.isEmpty()) return
        confirmation?.dismiss()
        confirmation = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tag_rename)
            .setItems(tags.map { it.name }.toTypedArray()) { _, index -> showNameDialog(tags[index]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun canClick() = view != null && !isHidden && !dragging && !busy &&
        SystemClock.uptimeMillis() >= ignoreClickUntil

    private fun openMembers(tag: TagGroup, edit: Boolean) {
        if (!canClick()) return
        captureScroll()
        (activity as? MainActivity)?.displayFragment(GroupFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TAG_ID, tag.id); putBoolean(ARG_TAG_EDIT, edit) }
        })
    }

    private fun confirmDelete(tag: TagGroup) {
        if (!canClick()) return
        confirmation?.dismiss()
        confirmation = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tag_delete_confirm)
            .setMessage(R.string.tag_delete_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tag_delete) { _, _ -> delete(tag) }.show()
    }

    private fun delete(tag: TagGroup) {
        runOperation {
            val record = withContext(Dispatchers.IO) { TagGroupStore.delete(tag.id) }
            if (record != null) {
                dismissUndo()
                val undo = PendingUndo(tag.name, record::restore)
                pendingUndo = undo
                showUndo(undo)
            }
        }
    }

    private fun showMessage(resource: Int) {
        val host = activity as? MainActivity ?: return
        if (view != null && !isHidden && !host.isFinishing && !host.isDestroyed) {
            host.snackbar(resource).show()
        }
    }

    private fun showUndo(undo: PendingUndo) {
        val host = activity as? MainActivity
        if (host == null || view == null || isHidden || host.isFinishing || host.isDestroyed) {
            if (pendingUndo === undo) pendingUndo = null
            return
        }
        // The original Neko snackbar owns the window (LENGTH_LONG, including accessibility).
        // Never start a second timer or replay the bar after hide/recreation/name changes.
        val bar = host.snackbar(getString(R.string.tag_deleted, undo.name))
        undoSnackbar = bar
        bar.setAction(R.string.tag_undo) {
            if (pendingUndo !== undo || undoSnackbar !== bar) return@setAction
            pendingUndo = null // Consume before IO: one complete store restore, no repeat-click race.
            undoSnackbar = null
            if (busy) return@setAction
            runOperation {
                withContext(Dispatchers.IO) { undo.restore() }
                showMessage(R.string.tag_restored)
            }
        }.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // An old bar may finish dismissing after a newer delete already replaced it.
                if (undoSnackbar !== transientBottomBar) return
                undoSnackbar = null
                if (event != DISMISS_EVENT_ACTION && pendingUndo === undo) pendingUndo = null
            }
        })
        updateUndoAction()
        bar.show()
    }

    private fun updateUndoAction() {
        undoSnackbar?.view?.findViewById<View>(com.google.android.material.R.id.snackbar_action)
            ?.isEnabled = !busy
    }

    private fun dismissUndo() {
        // Close synchronously as Material's dismissal callback can arrive after view teardown.
        pendingUndo = null
        val bar = undoSnackbar
        undoSnackbar = null
        bar?.dismiss()
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (busy || view == null) return
        busy = true
        updateUndoAction()
        val token = viewGeneration
        operationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == viewGeneration) showMessage(R.string.tag_operation_failed)
            } finally {
                if (token == viewGeneration && view != null) {
                    busy = false
                    updateUndoAction()
                    requestRefresh()
                }
            }
        }
    }

    private fun installDrag() {
        // Inspect DOWN before ItemTouchHelper's gesture detector. Long-pressing an action
        // button must not initiate RecyclerView drag (child touch listeners alone are late).
        list?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    bodyGesture = true
                    val child = recyclerView.findChildViewUnder(event.x, event.y)
                    if (child != null) {
                        val location = IntArray(2)
                        for (id in listOf(R.id.tag_edit, R.id.tag_delete)) {
                            val button = child.findViewById<View>(id)
                            button.getLocationOnScreen(location)
                            if (event.rawX >= location[0] && event.rawX < location[0] + button.width &&
                                event.rawY >= location[1] && event.rawY < location[1] + button.height) {
                                bodyGesture = false
                            }
                        }
                    }
                }
                return false
            }
        })
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = true
            override fun isItemViewSwipeEnabled() = false
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (busy || isHidden || !bodyGesture) 0 else super.getDragDirs(recyclerView, viewHolder)

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    refreshJob?.cancel()
                    dragging = true
                    orderChanged = false
                    viewHolder?.itemView?.isPressed = false
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val current = adapter ?: return false
                if (from !in current.rows.indices || to !in current.rows.indices) return false
                current.rows.add(to, current.rows.removeAt(from))
                current.notifyItemMoved(from, to)
                orderChanged = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!dragging) return
                dragging = false
                ignoreClickUntil = SystemClock.uptimeMillis() + 200L
                viewHolder.itemView.isPressed = false
                captureScroll()
                val ids = adapter?.rows?.map { it.tag.id }.orEmpty()
                val changed = orderChanged
                orderChanged = false
                if (changed) runOperation { withContext(Dispatchers.IO) { TagGroupStore.reorder(ids) } }
                else if (dirty) requestRefresh()
            }
        }).also { it.attachToRecyclerView(list) }
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        val rows = mutableListOf<Row>()
        init {
            setHasStableIds(true)
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        fun replace(next: List<Row>) { rows.clear(); rows.addAll(next); notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = rows[position].tag.id
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TagHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.layout_tag_group_item, parent, false))
        override fun onBindViewHolder(holder: TagHolder, position: Int) = holder.bind(rows[position])
    }

    private inner class TagHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.tag_name)
        private val count = view.findViewById<TextView>(R.id.tag_count)
        private val edit = view.findViewById<View>(R.id.tag_edit)
        private val delete = view.findViewById<View>(R.id.tag_delete)
        private var tag: TagGroup? = null
        init {
            itemView.setOnClickListener { tag?.let { openMembers(it, false) } }
            // Child actions consume down/long-press, so the body-only drag gesture cannot
            // be started from an edit/delete button and cannot trigger a click on release.
            for (button in listOf(edit, delete)) {
                button.setOnLongClickListener { true }
                button.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemView.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        itemView.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
            edit.setOnClickListener { tag?.let { openMembers(it, true) } }
            delete.setOnClickListener { tag?.let(::confirmDelete) }
        }
        fun bind(row: Row) {
            tag = row.tag
            name.text = row.tag.name
            count.text = getString(R.string.tag_count, row.count)
            edit.contentDescription = "${getString(R.string.tag_edit)} · ${row.tag.name}"
            delete.contentDescription = "${getString(R.string.tag_delete)} · ${row.tag.name}"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureScroll()
        outState.putParcelable(STATE_SCROLL, savedScroll)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        captureScroll()
        viewGeneration++
        confirmation?.dismiss()
        confirmation = null
        dismissUndo()
        refreshJob?.cancel()
        refreshJob = null
        operationJob?.cancel()
        operationJob = null
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        list?.adapter = null
        list?.layoutManager = null
        list = null
        adapter = null
        layoutManager = null
        empty = null
        backCallback = null
        dragging = false
        busy = false
        orderChanged = false
        super.onDestroyView()
    }
}
