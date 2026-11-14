package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.GuardedProcessPool
import kotlinx.coroutines.*
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.util.concurrent.Executors

/** The real OS child exists while Main guard dispatch is paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class,
    shadows = [GuardedProcessCancellationTest.NoNativeLogging::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GuardedProcessCancellationTest {
    @Implements(value = Libcore::class, isInAndroidSdk = false)
    class NoNativeLogging {
        companion object {
            @Implementation @JvmStatic fun __staticInitializer__() = Unit
            @Implementation @JvmStatic fun nekoLogPrintln(message: String) = Unit
        }
    }

    private fun alive(pid: Long): Boolean = File("/proc/$pid").exists()

    @Test fun processStartedBeforeGuardDispatchIsStillDestroyedOnCancel() = runBlocking {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(
            io.nekohasekai.sagernet.SagerNet(), "attachBaseContext",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, appContext))
        val pidFile = File.createTempFile("guard-cancel-pid", ".txt")
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val pool = GuardedProcessPool { }
        try {
            withContext(executor) {
                pool.start(listOf("/bin/sh", "-c", "echo \$\$ > '${pidFile.absolutePath}'; exec cat"))
            }
            // Child may start before its paused Main looper has entered.
            withTimeout(2000) { while (pidFile.readText().trim().toLongOrNull() == null) delay(5) }
            val pid = pidFile.readText().trim().toLong()
            assertTrue(alive(pid))
            pool.close(scope)
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(2500) {
                while (alive(pid)) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertFalse(alive(pid))
        } finally {
            pool.close(scope)
            shadowOf(Looper.getMainLooper()).idle()
            scope.cancel(); executor.close(); pidFile.delete()
        }
    }
}
