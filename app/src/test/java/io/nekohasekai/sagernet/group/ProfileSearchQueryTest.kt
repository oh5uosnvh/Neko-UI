package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.ProfileSearchQuery
import io.nekohasekai.sagernet.ui.ProfileSearchGeneration
import org.junit.Assert.*
import org.junit.Test

class ProfileSearchQueryTest {
    data class Node(val id:Long,val group:Long,val name:String)
    private val nodes=listOf(Node(1,10,"新加坡 01"),Node(2,20,"新加坡 02"),Node(3,10,"日本"))
    private fun select(query:ProfileSearchQuery)=query.select(nodes,{it.group},{node,text->node.name.contains(text)},{it.id==99L}).map{it.id}
    @Test fun sameTextGlobalThenGroupAlwaysReturnsOnlyCurrentGroupAgain() {
        assertEquals(listOf(1L),select(ProfileSearchQuery("新加坡",10,false)))
        assertEquals(listOf(1L,2L),select(ProfileSearchQuery("新加坡",10,true)))
        assertEquals(listOf(1L),select(ProfileSearchQuery("新加坡",10,false)))
        assertEquals(listOf(2L),select(ProfileSearchQuery("新加坡",20,false)))
    }
    @Test fun blankQueryClearsGlobalSnapshotToRealGroupAndPendingNodesAreExcluded() {
        assertEquals(listOf(1L,3L),select(ProfileSearchQuery("",10,true)))
        val query=ProfileSearchQuery("新加坡",10,true)
        assertEquals(listOf(1L),query.select(nodes,{it.group},{node,text->node.name.contains(text)},{it.id==2L}).map{it.id})
    }
    @Test fun newerSameTextScopeAndReloadRejectEveryOlderResponse() {
        val gate=ProfileSearchGeneration()
        val group=gate.begin(ProfileSearchQuery("新加坡",10,false))
        val global=gate.begin(ProfileSearchQuery("新加坡",10,true))
        val back=gate.begin(ProfileSearchQuery("新加坡",10,false))
        assertFalse(gate.accepts(group));assertFalse(gate.accepts(global));assertTrue(gate.accepts(back))
        val reload=gate.begin(ProfileSearchQuery("新加坡",10,false))
        assertFalse(gate.accepts(back));assertTrue(gate.accepts(reload))
        gate.invalidate();assertFalse(gate.accepts(reload));assertNull(gate.currentQuery)
    }
}
