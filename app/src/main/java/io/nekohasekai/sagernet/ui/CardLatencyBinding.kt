package io.nekohasekai.sagernet.ui

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.widget.DottedLatencyDrawable

/** Rebinding finished rows is synchronous; only a live test result transitions once. */
internal class CardLatencyBinding(private val label: TextView, private val legacyPending: View,
    private val now: () -> Long = SystemClock::uptimeMillis) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val spinner = DottedLatencyDrawable()
    private var token = 0L
    private var delayed: Runnable? = null

    fun bind(entry: CardLatencySession.Entry?, hasResult: Boolean) {
        reset()
        if (entry == null || entry.revealed) return
        val resultText = label.text
        val resultColor = label.currentTextColor
        if (hasResult && entry.remaining(now()) == 0L) {
            // New visible holder with an already completed result never replays it.
            entry.revealed = true
            return
        }
        label.text = ""
        val size = (14 * label.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        spinner.setBounds(0, 0, size, size)
        spinner.setTint(resultColor)
        label.setCompoundDrawables(null, null, spinner, null)
        spinner.start()
        if (!hasResult) return
        val ticket = token
        val reveal = object : Runnable {
            override fun run() {
                if (ticket != token) return
                val remaining = entry.remaining(now())
                if (remaining > 0L) { handler.postDelayed(this, remaining); return }
                entry.revealed = true
                spinner.stop()
                label.setCompoundDrawables(null, null, null, null)
                label.text = resultText
                label.setTextColor(resultColor)
                label.alpha = 1f
                label.scaleX = 1f; label.scaleY = 1f
                delayed = null
            }
        }
        delayed = reveal
        handler.postDelayed(reveal, entry.remaining(now()))
    }

    fun reset() {
        token++
        delayed?.let { handler.removeCallbacks(it) }; delayed = null
        spinner.stop()
        label.animate().cancel()
        legacyPending.animate().cancel()
        legacyPending.visibility = View.GONE
        legacyPending.alpha = 1f
        label.setCompoundDrawables(null, null, null, null)
        label.alpha = 1f; label.scaleX = 1f; label.scaleY = 1f
    }
}
