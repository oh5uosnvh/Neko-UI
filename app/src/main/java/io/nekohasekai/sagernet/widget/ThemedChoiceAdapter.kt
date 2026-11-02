package io.nekohasekai.sagernet.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.nekohasekai.sagernet.R

/** Shared by preference spinners and the route card popup; selection is read on each bind. */
internal class ThemedChoiceAdapter(
    context: Context,
    private val selection: () -> Int,
) : ArrayAdapter<CharSequence?>(context, R.layout.layout_choice_menu_item, R.id.choice_menu_label) {
    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
        bind(position, recycled, parent)

    override fun getDropDownView(position: Int, recycled: View?, parent: ViewGroup): View {
        // Spinner's DropDownAdapter hands us its actual ListView. Suppress the old rectangular
        // selector there, without theme-wide side effects on unrelated menus or touch callbacks.
        (parent as? android.widget.ListView)?.let { ThemedChoiceStyle.clipMenu(it) }
        return bind(position, recycled, parent)
    }

    private fun bind(position: Int, recycled: View?, parent: ViewGroup): View {
        val row = recycled?.takeIf { it.findViewById<TextView>(R.id.choice_menu_label) != null }
            ?: LayoutInflater.from(context).inflate(R.layout.layout_choice_menu_item, parent, false)
        val label = row.findViewById<TextView>(R.id.choice_menu_label)
        label.text = getItem(position)
        ThemedChoiceStyle.bind(row, label, position == selection())
        return row
    }
}
