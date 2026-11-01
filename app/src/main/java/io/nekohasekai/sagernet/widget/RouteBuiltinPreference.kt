package io.nekohasekai.sagernet.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.assets.KaringResourceManager
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Searchable secondary selector; manually typed and unavailable references remain editable. */
class RouteBuiltinPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.preference.Preference(context, attrs) {
    override fun onClick() {
        val activity = context as? AppCompatActivity ?: return
        showSelector(activity, DataStore.routeBuiltinRulesets) {
            DataStore.routeBuiltinRulesets = it
            notifyChanged()
        }
    }

    companion object {
        fun showSelector(activity: AppCompatActivity, value: String, onApply: (String) -> Unit) {
            val original = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val selected = original.toMutableSet()
            val density = activity.resources.displayMetrics.density
            val padding = (16 * density).toInt()
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
            val search = EditText(activity).apply { hint = activity.getString(R.string.karing_search_rules); setSingleLine() }
            val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
            layout.addView(search)
            layout.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (400 * density).toInt()))
            val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.route_builtin_rulesets)
                .setView(layout).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create()
            val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
            list.adapter = adapter
            var all = emptyList<String>()
            var visible = emptyList<String>()
            var loaded = false
            fun filter() {
                val query = search.text.toString().trim()
                visible = all.filter { it.contains(query, ignoreCase = true) }
                adapter.clear(); adapter.addAll(visible); adapter.notifyDataSetChanged()
                list.clearChoices()
                visible.forEachIndexed { index, item -> list.setItemChecked(index, item in selected) }
            }
            list.setOnItemClickListener { _, _, position, _ ->
                val item = visible[position]
                if (list.isItemChecked(position)) selected.add(item) else selected.remove(item)
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
                override fun afterTextChanged(s: Editable?) {}
            })
            dialog.setOnShowListener {
                val ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                ok.isEnabled = false
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val manager = KaringResourceManager(activity)
                            KaringRuleFamily.values().flatMap { family -> manager.list(family).filter { it.bundled }.map { "${family.wireName}:${it.code}" } }
                        }
                    }
                    if (!dialog.isShowing) return@launch
                    result.onSuccess { choices ->
                        all = (original + choices).distinct()
                        loaded = true; filter(); ok.isEnabled = true
                    }.onFailure {
                        Toast.makeText(activity, R.string.karing_rules_load_failed, Toast.LENGTH_LONG).show()
                    }
                }
                ok.setOnClickListener {
                    if (!loaded) return@setOnClickListener
                    // Existing unknown references survive unless explicitly deselected; newly
                    // selected bundled resources are installed only when confirmed.
                    ok.isEnabled = false
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val manager = KaringResourceManager(activity)
                                for (reference in selected - original.toSet()) {
                                    val parts = reference.split(":", limit = 2)
                                    val family = KaringRuleFamily.parse(parts[0])
                                    require(parts.size == 2 && family != null)
                                    manager.ensure(family, parts[1])
                                }
                            }
                        }
                        if (!dialog.isShowing) return@launch
                        result.onSuccess {
                            onApply((original.filter { it in selected } + selected.filter { it !in original }).joinToString("\n"))
                            dialog.dismiss()
                        }.onFailure {
                            ok.isEnabled = true
                            Toast.makeText(activity, R.string.karing_rules_install_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            dialog.show()
        }
    }
}
