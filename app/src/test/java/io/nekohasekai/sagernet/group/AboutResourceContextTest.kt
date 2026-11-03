package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.AboutResourceContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AboutResourceContextTest {
    @Test fun detachedTaskNullActivityStillUsesAttachedResourceSnapshot() {
        val app=RuntimeEnvironment.getApplication()
        val config=Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val themed=app.createConfigurationContext(config)
        val snapshot=AboutResourceContext.snapshot(themed)
        val resolved=AboutResourceContext.resolve(null,snapshot,app)
        assertSame(snapshot,resolved)
        assertEquals(snapshot.getString(R.string.version_x,"sing-box"),AboutResourceContext.versionLabel(resolved,"sing-box"))
        assertEquals(Configuration.UI_MODE_NIGHT_YES,resolved.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertNotNull(AboutResourceContext.resolve(null,null,app))
    }
}
