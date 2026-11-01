package io.nekohasekai.sagernet.widget

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.assets.KaringResourceManager
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.linesClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Indexed Karing selector. Only bundled entries are offered; selection installs and validates lazily. */
class RouteBuiltinPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.preference.Preference(context, attrs) {
    private val manager by lazy { KaringResourceManager(context) }

    override fun getSummary(): CharSequence = DataStore.routeBuiltinRulesets.linesClean()
        .takeIf { it.isNotEmpty() }?.joinToString("\n") ?: context.getString(androidx.preference.R.string.not_set)

    override fun onClick() {
        val choices = KaringRuleFamily.values().flatMap { family ->
            manager.list(family).filter { it.bundled }.map { "${family.wireName}:${it.code}" }
        }
        val selected = DataStore.routeBuiltinRulesets.linesClean().toMutableSet()
        val checked = choices.map { selected.contains(it) }.toBooleanArray()
        AlertDialog.Builder(context).setTitle(title).setMultiChoiceItems(
            choices.toTypedArray(), checked
        ) { _, which, isChecked ->
            if (isChecked) selected.add(choices[which]) else selected.remove(choices[which])
        }.setPositiveButton(android.R.string.ok) { _, _ ->
            val owner = context as? LifecycleOwner
            if (owner == null) return@setPositiveButton
            owner.lifecycleScope.launch(Dispatchers.IO) {
                val usable = selected.filter { reference ->
                    val split = reference.split(":", limit = 2)
                    val family = KaringRuleFamily.parse(split[0])
                    family != null && runCatching {
                        manager.ensure(family, split[1])
                    }.isSuccess
                }
                withContext(Dispatchers.Main) {
                    DataStore.routeBuiltinRulesets = usable.joinToString("\n")
                    notifyChanged()
                    if (usable.size != selected.size) {
                        Toast.makeText(context, "Some Karing rule sets failed validation", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
