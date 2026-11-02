package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.RouteOutboundStore
import io.nekohasekai.sagernet.widget.RouteOutboundMenu
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class RouteQuickOutboundTest {
    @Test fun selectingOutboundPersistsOnlyThatField() {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val rule=RuleEntity(name="sentinel",domains="geosite:google",ip="192.0.2.0/24",enabled=true,userOrder=7L,config="{\"sentinel\":true}")
            rule.id=db.rulesDao().createRule(rule)
            for (outbound in listOf(-1L,-2L,0L)) {
                val result=RouteOutboundStore.set(db,rule.id,outbound)!!
                assertEquals(outbound,result.outbound);assertEquals("geosite:google",result.domains)
                assertEquals("192.0.2.0/24",result.ip);assertEquals(7L,result.userOrder);assertTrue(result.enabled)
                assertEquals(rule.config,result.config)
            }
            db.rulesDao().updateEnabled(rule.id,false)
            RouteOutboundStore.set(db,rule.id,-2L)
            db.rulesDao().updateOrder(rule.id,99)
            val fresh=db.rulesDao().getById(rule.id)!!
            assertFalse(fresh.enabled);assertEquals(99L,fresh.userOrder);assertEquals(-2L,fresh.outbound)
        } finally {db.close()}
    }
    @Test fun chooseProfileRequiresExistingProfileAndRuleStillExists() {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val rule=RuleEntity(enabled=true);rule.id=db.rulesDao().createRule(rule)
            assertNull(RouteOutboundStore.set(db,rule.id,9876543))
            assertEquals(0L,db.rulesDao().getById(rule.id)!!.outbound)
            val profile=ProxyEntity(groupId=1).apply{putBean(SOCKSBean().apply{initializeDefaultValues()})}
            profile.id=db.proxyDao().addProxy(profile)
            assertEquals(profile.id,RouteOutboundStore.set(db,rule.id,profile.id)!!.outbound)
            db.rulesDao().deleteById(rule.id)
            assertNull(RouteOutboundStore.set(db,rule.id,0))
        } finally {db.close()}
    }
    @Test fun cardPopupUsesExistingFourOptionsAndSelectionIsImmediate() {
        val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity=controller.get().apply{setTheme(R.style.Theme_SagerNet)}
        controller.setup().visible()
        try {
            val anchor=TextView(activity).apply{text="代理"}
            activity.setContentView(FrameLayout(activity).apply{addView(anchor)})
            shadowOf(Looper.getMainLooper()).idle()
            var chosen=-1
            val popup=RouteOutboundMenu.show(anchor,-1L){chosen=it}
            val list=popup.listView!!
            assertEquals(activity.resources.getStringArray(R.array.outbound_entry).toList(),(0 until list.adapter.count).map{list.adapter.getItem(it).toString()})
            assertEquals(1,list.selectedItemPosition)
            popup.performItemClick(2)
            assertEquals(2,chosen);assertFalse(popup.isShowing)
        } finally {controller.pause().stop().destroy()}
    }
}
