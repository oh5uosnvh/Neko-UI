package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import org.json.JSONObject

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(internal val service: Interface) {
        @Volatile var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var pendingRestartEpoch: Long? = null
        var pendingUserStart: Intent? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> if (ServiceStartPolicy.permits(intent)) service.reload()
                Action.CLOSE -> service.stopRunner(userInitiated = true)
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            if (DataStore.wakeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    if (!state.canStop || !ServiceStartPolicy.fence.permitsAutomatic()) return@runOnDefaultDispatcher
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> Unit
            }
        }
        @Volatile var closeReceiverRegistered = false
        /** Serializes UI selector requests with service teardown. */
        val selectorMutationLock = Any()

        val binder = Binder(this)
        var connectingJob: Job? = null
        var probeJob: Job? = null
        var probeGeneration = 0L
        var probeProfileId = 0L
        @Volatile var connectionProbe = ""

        fun publishProbe(phase: String, elapsed: Int = -1, error: String? = null) {
            connectionProbe = JSONObject().put("version", 1).put("generation", probeGeneration)
                .put("profileId", probeProfileId).put("phase", phase).put("elapsed", elapsed)
                .put("error", error ?: "").toString()
            binder.connectionProbeChanged(connectionProbe)
        }

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"
        override fun getConnectionProbe(): String = data?.connectionProbe.orEmpty()

        override fun selectSelectorNode(profileId: Long): String {
            val current = data ?: return selectorError("SERVICE_GONE", "service is closed")
            synchronized(current.selectorMutationLock) {
                if (current.state != State.Connected) {
                    val code = if (current.state == State.Connecting) "BUSY" else "NOT_CONNECTED"
                    return selectorError(code, "service is ${current.state.name.lowercase()}")
                }
                val running = current.proxy ?: return selectorError("NO_PROXY", "proxy is not initialized")
                if (!running.config.globalSelector && running.config.selectorGroupId < 0L) {
                    return selectorError("NOT_SELECTOR", "running config has no selector")
                }
                if (!current.service.isCompiledSelectorCurrent(profileId)) {
                    return selectorError("STALE_CONFIG", "selector membership or policy changed")
                }
                val tag = running.config.profileTagMap[profileId]
                    ?: return selectorError("NOT_MEMBER", "profile is not compiled")
                if (!running.box.selectOutbound(tag)) {
                    return selectorError("CORE_REJECTED", "native selector rejected tag")
                }
                // The callback is asynchronous and a same-node SelectOutbound may
                // emit no callback. Persist only after native acknowledgement, and
                // only if teardown has not swapped the running ProxyInstance.
                if (current.proxy !== running) {
                    return selectorError("SERVICE_CHANGED", "running proxy changed during selection")
                }
                DataStore.selectedProxy = profileId
                DataStore.currentProfile = profileId
                return JSONObject().put("ok", true).put("ack", true)
                    .put("profileId", profileId).put("actualId", profileId)
                    .put("tag", tag).toString()
            }
        }

        private fun selectorError(code: String, message: String): String =
            JSONObject().put("ok", false).put("ack", false)
                .put("error", code).put("message", message).toString()

        fun connectionProbeChanged(snapshot: String) = launch {
            broadcast { it.connectionProbe(snapshot) }
        }

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, DataStore.connectionTestTimeout
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            // Reload is never an explicit start. A stale switch/apply callback may
            // arrive while Stopping, or after a bound service has reached Stopped.
            if (!ServiceStartPolicy.fence.permitsAutomatic() || !data.state.canStop) return
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            // Global node-card changes use selectSelectorNode (ACK, never reload).
            // This broadcast is an explicit settings/subscription Apply request;
            // changed runtime membership or policy must be rebuilt here.
            if (canReloadSelector()) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // Select on this exact running Box. A false native result is a
                    // hard rejection: never hide it by falling back to reload.
                    if (!data.proxy!!.box.selectOutbound(tag)) {
                        Logs.w("selector rejected compiled node id=${ent.id} tag=$tag")
                    }
                }
                return
            }
            val s = data.state
            when {
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        /** Validate the current database against the exact running selector. */
        fun isCompiledSelectorCurrent(id: Long): Boolean {
            val running = data.proxy ?: return false
            if (running.config.selectorGroupId < 0L) return false
            val ent = SagerDatabase.proxyDao.getById(id) ?: return false
            val runningTag = running.config.profileTagMap[ent.id] ?: return false
            if (runningTag.isBlank()) return false
            return try {
                val tmpBox = ProxyInstance(ent)
                tmpBox.buildConfigTmp()
                val rebuilt = tmpBox.config
                // Membership, chain order and selector policy must be exactly the
                // same. Group id alone is insufficient for cross-group selectors.
                rebuilt.selectorGroupId == running.config.selectorGroupId &&
                    rebuilt.globalSelector == running.config.globalSelector &&
                    rebuilt.selectorFingerprint == running.config.selectorFingerprint &&
                    rebuilt.profileTagMap[ent.id] == runningTag
            } catch (e: Throwable) {
                Logs.w("selector live switch rejected: ${e.message}")
                false
            }
        }

        fun canReloadSelector(): Boolean = isCompiledSelectorCurrent(DataStore.selectedProxy)

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner(epoch: Long = ServiceStartPolicy.fence.snapshot()) {
            if (!ServiceStartPolicy.fence.permitsAutomatic(epoch)) return
            this as Context
            val intent = ServiceStartPolicy.tag(Intent(this, javaClass), ServiceStartPolicy.RELOAD, epoch)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
        }

        fun killProcesses() {
            data.proxy?.close()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null, userInitiated: Boolean = false) {
            // Revoke the restart BEFORE the Stopping early return: a reload may
            // already own the cleanup coroutine, but notification Stop must win.
            if (userInitiated) {
                val epoch = ServiceStartPolicy.fence.userStop()
                data.pendingRestartEpoch = null
                data.pendingUserStart = null
                BootReceiver.enabled = false
                Logs.d("Service user stop: epoch=$epoch")
            }
            synchronized(data.selectorMutationLock) {
                if (data.state == State.Stopping) return
                data.pendingRestartEpoch = if (restart) ServiceStartPolicy.fence.snapshot() else null
                data.changeState(State.Stopping)
            }

            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false

            data.probeJob?.cancel()
            data.probeJob = null
            if (!msg.isNullOrBlank()) data.publishProbe("failed", error = msg)
            else data.publishProbe("idle")
            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    // Join listener removal before starting the replacement. An
                    // unjoined Default dispatcher stop could unregister its listener.
                    DefaultNetworkListener.stop(this@Interface)
                    data.proxy = null
                }

                // change the state
                data.changeState(State.Stopped, msg)
                // stop the service if nothing has bound to it
                val userStart = data.pendingUserStart
                val restartEpoch = data.pendingRestartEpoch
                data.pendingUserStart = null
                data.pendingRestartEpoch = null
                if (userStart != null && ServiceStartPolicy.permits(userStart)) {
                    // A new, explicit Connect tap during cleanup is not a stale retry.
                    onStartCommand(userStart, 0, 0)
                } else if (restartEpoch != null && ServiceStartPolicy.fence.permitsAutomatic(restartEpoch)) {
                    startRunner(restartEpoch)
                } else stopSelf()
            }
        }

        fun dispose() {
            // Bound services can stay alive in Stopped: keep CLOSE registered until
            // actual destruction so Stop can still cancel a queued replacement.
            if (data.closeReceiverRegistered) {
                (this as Context).unregisterReceiver(data.receiver)
                data.closeReceiverRegistered = false
            }
            data.binder.close()
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            DefaultNetworkListener.start(this) {
                if (!data.state.canStop || !ServiceStartPolicy.fence.permitsAutomatic()) return@start
                SagerNet.connectivity.getLinkProperties(it)?.also { link ->
                    SagerNet.underlyingNetwork = it
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                        if (DataStore.networkChangeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            val cause = when (intent?.getStringExtra(ServiceStartPolicy.EXTRA_CAUSE)) {
                ServiceStartPolicy.USER -> ServiceStartPolicy.USER
                ServiceStartPolicy.RELOAD -> ServiceStartPolicy.RELOAD
                ServiceStartPolicy.BOOT -> ServiceStartPolicy.BOOT
                else -> "system/legacy"
            }
            if (!ServiceStartPolicy.permits(intent)) {
                Logs.d("Service start rejected: cause=$cause state=${data.state} startId=$startId")
                if (data.state == State.Stopped) (this as Service).stopSelf()
                return Service.START_NOT_STICKY
            }
            if (data.state == State.Stopping) {
                if (cause == ServiceStartPolicy.USER) data.pendingUserStart = Intent(intent!!)
                return Service.START_NOT_STICKY
            }
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            if (!ServiceStartPolicy.admit(intent)) {
                (this as Service).stopSelf()
                return Service.START_NOT_STICKY
            }
            Logs.d("Service start accepted: cause=$cause startId=$startId")
            DataStore.baseService = this
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.probeGeneration++
            data.probeProfileId = profile.id
            data.changeState(State.Connecting)
            data.publishProbe("connecting")
            data.connectingJob = runOnMainDispatcher {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        // A fatal callback dispatched by an old pool must not stop
                        // its replacement after a user start or a profile switch.
                        if (data.proxy === proxy && data.state.canStop &&
                            ServiceStartPolicy.fence.permitsAutomatic()) {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }
                    }

                    startProcesses()
                    // Connected means the real core/VPN tunnel was established. A temporary
                    // URL failure is a measurement failure, never an instruction to stop VPN.
                    data.changeState(State.Connected)
                    val probeGeneration = data.probeGeneration
                    data.probeJob?.cancel()
                    data.probeJob = runOnMainDispatcher {
                        try {
                            val result = StartupConnectionProbe.measure(DataStore.connectionTestTimeout) {
                                Libcore.urlTest(proxy.box, DataStore.connectionTestURL, DataStore.connectionTestTimeout)
                            }
                            if (data.proxy !== proxy || data.state != State.Connected ||
                                data.probeGeneration != probeGeneration || DataStore.selectedProxy != profile.id) return@runOnMainDispatcher
                            if (result.error != null) {
                                data.publishProbe("failed", error = Protocols.genFriendlyMsg(result.error))
                            } else data.publishProbe("connected", elapsed = result.elapsed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (data.proxy === proxy && data.state == State.Connected && data.probeGeneration == probeGeneration)
                                data.publishProbe("failed", error = Protocols.genFriendlyMsg(error.readableMessage))
                        }
                    }
                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            // Preserve recovery after an unexpected process kill. A persisted
            // user-stop fence rejects sticky/null intents before admission.
            return Service.START_STICKY
        }
    }

}
