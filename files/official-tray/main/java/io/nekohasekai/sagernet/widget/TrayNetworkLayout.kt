package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import io.nekohasekai.sagernet.R
import kotlin.math.max

/** Two rows share a start edge. The symmetric info hit box extends 10dp
 * past the text edge, so its visible circle and the address share an end. */
class TrayNetworkLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun child(id: Int): View = findViewById(id)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(200) else MeasureSpec.getSize(widthMeasureSpec)
        val textLimit = (maxWidth-dp(10)).coerceAtLeast(1).coerceAtMost(dp(180))
        val flag=child(R.id.bar_ip_flag); val title=child(R.id.bar_network_title)
        val info=child(R.id.bar_ip_info); val ip=child(R.id.bar_ip_value)
        val atMost=MeasureSpec.makeMeasureSpec(textLimit,MeasureSpec.AT_MOST)
        val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        flag.measure(atMost,free)
        val titleLimit=(textLimit-flag.measuredWidth-dp(32)).coerceAtLeast(1)
        title.measure(MeasureSpec.makeMeasureSpec(titleLimit,MeasureSpec.AT_MOST),free)
        info.measure(MeasureSpec.makeMeasureSpec(dp(40),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(40),MeasureSpec.EXACTLY))
        ip.measure(atMost,free)
        val header = flag.measuredWidth+dp(6)+title.measuredWidth+dp(6)+dp(20)
        // Header owns the width: changing the IP cannot move the title/icon
        // apart. Long addresses scale/ellipsize inside that same column.
        val textWidth=header.coerceAtMost(textLimit)
        ip.measure(MeasureSpec.makeMeasureSpec(textWidth,MeasureSpec.EXACTLY),free)
        val headHeight=max(dp(40),max(flag.measuredHeight,title.measuredHeight))
        setMeasuredDimension(resolveSize(textWidth+dp(10),widthMeasureSpec),resolveSize(headHeight+ip.measuredHeight,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val flag=child(R.id.bar_ip_flag);val title=child(R.id.bar_network_title)
        val info=child(R.id.bar_ip_info);val ip=child(R.id.bar_ip_value)
        val header=max(dp(40),max(flag.measuredHeight,title.measuredHeight))
        flag.layout(0,(header-flag.measuredHeight)/2,flag.measuredWidth,(header+flag.measuredHeight)/2)
        val titleLeft=flag.right+dp(6)
        title.layout(titleLeft,(header-title.measuredHeight)/2,titleLeft+title.measuredWidth,(header+title.measuredHeight)/2)
        info.layout(width-info.measuredWidth,(header-info.measuredHeight)/2,width,(header+info.measuredHeight)/2)
        ip.layout(0,header,ip.measuredWidth,header+ip.measuredHeight)
    }
}
