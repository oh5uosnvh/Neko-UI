package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.CardLatencySession
import org.junit.Assert.*
import org.junit.Test

class RepairedLatencyCacheTest {
    @Test fun repairedNodeDoesNotKeepHistoricalFailureOrAcceptLateResult() {
        val session=CardLatencySession()
        session.begin(listOf(4L,9L),1,now=0)
        session.complete(ProxyEntity(id=4,status=3,error="old failure"),1)
        session.complete(ProxyEntity(id=9,status=1,ping=20),1)
        session.invalidate(listOf(4))
        assertNull(session.get(4))
        assertEquals(20,session.get(9)!!.result!!.ping)
        session.complete(ProxyEntity(id=4,status=3,error="late stale failure"),1)
        assertNull(session.get(4))
        session.begin(listOf(4),2,now=0)
        session.complete(ProxyEntity(id=4,status=1,ping=30),2)
        assertEquals(30,session.get(4)!!.result!!.ping)
    }
}
