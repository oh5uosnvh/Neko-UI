package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.*

/** Presentation only. Official StatsBar owns show/hide, scroll and FAB cradle. */
class ConnectBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : StatsBar(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {
    private val ipValue: TextView
    private val flagValue: TextView
    var onTestConnection: (() -> Unit)? = null
    private val surfaceFeedback by lazy { TraySurfaceFeedback(this) }
    override fun draw(canvas: Canvas) {
        surfaceFeedback.syncShape()
        super.draw(canvas)
    }
    init {
        surfaceFeedback.syncShape()
        inflate(context, R.layout.layout_connect_bar, this)
        ipValue = findViewById(R.id.bar_ip_value); flagValue = findViewById(R.id.bar_ip_flag)
        // Also initializes StatsBar's official tx/rx/status bindings.
        TrayTapTargets.bind(this,
            onTest = { if (DataStore.serviceState.connected) onTestConnection?.invoke() },
            onInfo = { openNetworkDialog() },
        )
        updateSpeed(0,0); renderNetwork()
    }
    private fun openNetworkDialog() {
        val activity = context as? FragmentActivity ?: return
        if (!activity.supportFragmentManager.isStateSaved && activity.supportFragmentManager.findFragmentByTag(OutboundIpDialogFragment.DIALOG_TAG) == null) {
            OutboundIpDialogFragment().show(activity.supportFragmentManager, OutboundIpDialogFragment.DIALOG_TAG)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); NetworkPrivacy.prefs(context).registerOnSharedPreferenceChangeListener(this); renderNetwork() }
    override fun onDetachedFromWindow() { NetworkPrivacy.prefs(context).unregisterOnSharedPreferenceChangeListener(this); super.onDetachedFromWindow() }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) { if (key == NetworkPrivacy.HIDE_IP) post { renderNetwork() } }
    fun renderNetwork(q: IpQualityLookup.IpQuality? = null) {
        val entry = q ?: IpQualityLookup.cacheFor(DataStore.currentProfile)?.quality
        val hidden = NetworkPrivacy.hidden(context)
        ipValue.text = if (entry == null && !hidden && DataStore.serviceState.connected) context.getString(R.string.ip_loading)
            else NetworkPrivacy.display(entry?.ip, hidden)
        flagValue.text = NetworkPrivacy.flag(entry?.country)
    }
    fun setTesting() { findViewById<TextView>(R.id.status).setText(R.string.neko_handshake_testing) }
    fun showDelay(ms: Int) { findViewById<TextView>(R.id.status).text = context.getString(R.string.neko_handshake_ms, ms) }
    fun showDelayError() { findViewById<TextView>(R.id.status).setText(R.string.neko_handshake_failed) }
}
