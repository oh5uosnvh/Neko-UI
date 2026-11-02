package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.widget.ProfileDisplayRefresh
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AddressDisplayRefreshTest {
    @Test fun displayToggleRebindsIdenticalCachedNodesWithoutAppRestart() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=controller.get()
        try {
            var show=false
            var binds=0
            val list=RecyclerView(activity).apply{layoutManager=LinearLayoutManager(activity)}
            val adapter=object:RecyclerView.Adapter<Holder>(){
                override fun getItemCount()=2
                override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(TextView(parent.context).apply{layoutParams=RecyclerView.LayoutParams(-1,80)})
                override fun onBindViewHolder(h:Holder,p:Int){binds++;h.label.text=if(show) "node$p.example:443" else ""}
            }
            list.adapter=adapter;activity.setContentView(list)
            fun layout(){list.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));list.layout(0,0,800,600);shadowOf(Looper.getMainLooper()).idle()}
            layout()
            val before=binds
            show=true;ProfileDisplayRefresh.apply(list,adapter);layout()
            assertTrue(binds>before);assertEquals("node0.example:443",(list.findViewHolderForAdapterPosition(0) as Holder).label.text.toString())
            show=false;ProfileDisplayRefresh.apply(list,adapter);layout()
            assertEquals("",(list.findViewHolderForAdapterPosition(0) as Holder).label.text.toString())
        } finally {controller.pause().stop().destroy()}
    }
    class Holder(val label:TextView):RecyclerView.ViewHolder(label)

    @Test fun listenerRefreshesCachedAndRestoredPagesRatherThanReloadingIdenticalDatabaseRows() {
        val app=sequenceOf(java.io.File("."),java.io.File("app")).first{java.io.File(it,"src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").isFile}
        val source=java.io.File(app,"src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val block=source.substringAfter("private fun refreshAddressDisplayPreferences()").substringBefore("inner class GroupPagerAdapter")
        assertTrue(block.contains("childFragmentManager.fragments"))
        assertTrue(block.contains("refreshAddressDisplay()"))
        assertFalse(block.contains("reloadProfiles()"))
        assertTrue(source.contains("ProfileDisplayRefresh.apply(configurationListView, currentAdapter)"))
    }
}
