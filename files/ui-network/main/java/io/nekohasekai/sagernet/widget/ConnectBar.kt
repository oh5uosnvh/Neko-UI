package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.SharedPreferences
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ui.*

/** Official BottomAppBar/FAB cradle; only the information content is customized. */
class ConnectBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {
    private val txText: TextView
    private val rxText: TextView
    private val delayValue: TextView
    private val ipValue: TextView
    private val flagValue: TextView
    private var quality: IpQualityLookup.IpQuality? = null
    var onTestConnection: (() -> Unit)? = null

    init {
        inflate(context, R.layout.layout_connect_bar, this)
        txText = findViewById(R.id.bar_tx); rxText = findViewById(R.id.bar_rx)
        delayValue = findViewById(R.id.bar_delay_value)
        ipValue = findViewById(R.id.bar_ip_value); flagValue = findViewById(R.id.bar_ip_flag)
        isClickable = true
        val speedZone = findViewById<View>(R.id.bar_speed_zone)
        speedZone.setOnClickListener {
            if (DataStore.serviceState.connected) { setTesting(); onTestConnection?.invoke() }
        }
        findViewById<View>(R.id.bar_delay_zone).setOnClickListener { speedZone.performClick() }
        findViewById<View>(R.id.bar_network_card).setOnClickListener { openNetworkDialog() }
        findViewById<AppCompatImageView>(R.id.bar_ip_info).setOnClickListener { openNetworkDialog() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val margin = dp2px(4)+nav
                if (lp.bottomMargin != margin) { lp.bottomMargin=margin; v.requestLayout() }
            }; insets
        }
        updateSpeed(0,0); renderNetwork()
    }
    private fun openNetworkDialog() {
        val activity = context as? FragmentActivity ?: return
        if (activity.supportFragmentManager.isStateSaved) return
        if (activity.supportFragmentManager.findFragmentByTag(OutboundIpDialogFragment.DIALOG_TAG) == null) {
            OutboundIpDialogFragment().show(activity.supportFragmentManager, OutboundIpDialogFragment.DIALOG_TAG)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); NetworkPrivacy.prefs(context).registerOnSharedPreferenceChangeListener(this); renderNetwork() }
    override fun onDetachedFromWindow() { NetworkPrivacy.prefs(context).unregisterOnSharedPreferenceChangeListener(this); super.onDetachedFromWindow() }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) { if (key == NetworkPrivacy.HIDE_IP) post { renderNetwork() } }
    fun renderNetwork(q: IpQualityLookup.IpQuality? = null) {
        quality = q ?: IpQualityLookup.cacheFor(DataStore.currentProfile)?.quality
        ipValue.text = NetworkPrivacy.display(quality?.ip, NetworkPrivacy.hidden(context))
        flagValue.text = NetworkPrivacy.flag(quality?.country)
        // Intentionally no quality-based address coloring: theme text color only.
    }
    fun changeState(state: BaseService.State, animate: Boolean = true) {
        if (state != BaseService.State.Connected) delayValue.setText(R.string.neko_handshake_idle)
        if (state != BaseService.State.Connected) updateSpeed(0,0)
    }
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${context.getString(R.string.speed, Formatter.formatFileSize(context, txRate))}"
        rxText.text = "▼  ${context.getString(R.string.speed, Formatter.formatFileSize(context, rxRate))}"
    }
    fun setTesting() { delayValue.setText(R.string.neko_handshake_testing) }
    fun showDelay(ms: Int) { delayValue.text=context.getString(R.string.neko_handshake_ms,ms) }
    fun showDelayError() { delayValue.setText(R.string.neko_handshake_failed) }
}
