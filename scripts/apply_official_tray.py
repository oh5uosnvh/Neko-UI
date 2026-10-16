#!/usr/bin/env python3
from pathlib import Path
import re, shutil, subprocess, sys
r=Path(sys.argv[1]).resolve();kit=Path(__file__).resolve().parents[1]
def edit(rel,old,new):
 p=r/rel;s=p.read_text()
 if new and new in s:return
 if s.count(old)!=1:raise SystemExit(f'Tray anchor mismatch: {rel} ({s.count(old)})')
 p.write_text(s.replace(old,new,1))
root='app/src/main/java/io/nekohasekai/sagernet/'
main=root+'ui/MainActivity.kt';cfg=root+'ui/ConfigurationFragment.kt';lookup=root+'ui/IpQualityLookup.kt'
# Restore the ORIGINAL StatsBar state machine, scroll driver and show/hide behavior.
rel=root+'widget/StatsBar.kt'
s=subprocess.check_output(['git','-C',str(r),'show','f167ebc88e9a733e65380e13dab39726eba917b2:'+rel],text=True)
s=s.replace('class StatsBar @JvmOverloads','open class StatsBar @JvmOverloads')
(r/rel).write_text(s)
shutil.copytree(kit/'files/official-tray',r/'app/src',dirs_exist_ok=True)
# Match the official BottomAppBar placement exactly: no legacy compact-bar margin.
edit('app/src/main/res/layout/layout_main.xml',
    'android:layout_gravity="bottom" android:layout_marginBottom="4dp"',
    'android:layout_gravity="bottom" android:nextFocusUp="@id/fab"')
edit('app/src/main/res/layout/layout_main.xml',
    'app:fabAlignmentMode="center" app:hideOnScroll="false"',
    'app:fabAlignmentMode="center" app:hideOnScroll="true"')
edit(main,'binding.connectBar.changeState(state, animate)','''binding.connectBar.changeState(state)
        io.nekohasekai.sagernet.widget.TrayStatusText.apply(binding.connectBar, state)
        syncConnectBar(animate)''')
p=r/main;s=p.read_text();a=s.index('    private fun syncConnectBar() {');b=s.index('\n    // may NOT called',a)
s=s[:a]+'''    private fun syncConnectBar(animate: Boolean = false) {
        val showControls = currentMainFragment is ConfigurationFragment
        binding.connectBar.useExternalScrollDriver = showControls
        binding.connectBar.visibility = View.VISIBLE
        binding.connectBar.syncMainControls(showControls, lastObservedState,
            lastObservedState == BaseService.State.Connected, animate)
        binding.fab.animate().cancel()
        if (showControls) binding.fab.show() else binding.fab.hide()
    }

    fun driveBottomBar(scrollDy: Int) { binding.connectBar.onListScrolled(scrollDy) }
''' +s[b:];p.write_text(s)
# Do not start slow/direct requests before VPN connects. Query only on ready connection.
edit(main,'        refreshNetworkCard()\n        changeState(', '        observeNetworkResults()\n        changeState(')
edit(main,'            if (state == BaseService.State.Stopped || state == BaseService.State.Idle) refreshNetworkCard()', '''            IpQualityLookup.invalidate()
            binding.connectBar.renderNetwork()''')
a=(r/main).read_text().index('    private fun refreshNetworkCard() {');s=(r/main).read_text();b=s.index('\n    suspend fun importSubscription',a)
s=s[:a]+'''    private fun observeNetworkResults() {
        lifecycleScope.launch {
            IpQualityLookup.updates.collect { entry ->
                if (entry != null && entry.profileId == DataStore.currentProfile && DataStore.serviceState.connected) {
                    binding.connectBar.renderNetwork(entry.quality)
                }
            }
        }
    }

    private fun refreshNetworkCard() {
        networkJob?.cancel()
        IpQualityLookup.invalidate()
        binding.connectBar.renderNetwork()
        if (!DataStore.serviceState.connected) return
        val profileId = DataStore.currentProfile
        networkJob = lifecycleScope.launch {
            try { IpQualityLookup.lookup(profileId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e) }
        }
    }
''' +s[b:];p.write_text(s)
edit(main,'import kotlinx.coroutines.CancellationException\n', 'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.flow.collect\n')
# Anchor snackbars to the official FAB; hidden StatsBar must not cover Undo.
edit(main,'            anchorView = binding.connectBar', '            if (binding.fab.isShown) anchorView = binding.fab')
# Restore the upstream list scroll driver verbatim (including short-list drag behavior).
original=subprocess.check_output(['git','-C',str(r),'show','f167ebc88e9a733e65380e13dab39726eba917b2:'+cfg],text=True)
a=original.index('        private fun setupBottomBarScrollDriver() {');b=original.index('\n        ',original.index('\n        }',a)+10) if False else 0
# Balanced braces avoid guessing adjacent methods.
level=0;start=original.index('{',a)
for i in range(start,len(original)):
 if original[i]=='{':level+=1
 if original[i]=='}':
  level-=1
  if level==0: end=i+1;break
method=original[a:end]
edit(cfg,'            configurationListView.setItemViewCacheSize(20)', '            configurationListView.setItemViewCacheSize(20)\n            if (!select) setupBottomBarScrollDriver()')
edit(cfg,'        inner class ConfigurationAdapter :',method+'\n\n        inner class ConfigurationAdapter :')
edit(cfg,'bottom = dp2px(170) + insets.getInsets(', 'bottom = dp2px(116) + insets.getInsets(')
# Async cancellable HTTP calls, no blocking execute() in child coroutines.
edit(lookup,'    private const val COUNTRY_GRACE_MS = 1_500L     // 胜者缺国家时的宽限期\n','')
edit(lookup,'    private const val TOTAL_TIMEOUT_MS = 10_000L    // 六源对冲总超时\n','')
edit(lookup,'import kotlinx.coroutines.Dispatchers\n', '''import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
''')
edit(lookup,'    fun invalidate() { cache = null }', '''    private val live = MutableStateFlow<CacheEntry?>(null)
    val updates = live.asStateFlow()
    private var generation = 0L
    @Synchronized fun invalidate() { generation++; cache = null; live.value = null }
    @Synchronized internal fun epoch(): Long = generation
    @Synchronized internal fun publishIfCurrent(q: IpQuality, profileId: Long, epoch: Long): Boolean {
        if (generation != epoch) return false
        val entry = CacheEntry(q, profileId, System.currentTimeMillis())
        cache = entry; live.value = entry
        return true
    }''')
p=r/lookup;s=p.read_text();a=s.index('    suspend fun lookup(forProfile: Long): IpQuality {');b=s.index('    private class SourceFailure',a)
s=s[:a]+'''    suspend fun lookup(forProfile: Long): IpQuality = coroutineScope {
        val epoch = epoch()
        val detailed = async {
            try { doLookup() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        try {
            val first = FirstReplyRace.first(listOf(
                { detailed.await() },
                { quickAddress("https://api.ipify.org?format=json") },
                { quickAddress("https://ipinfo.io/json") },
            ), 5_000L) ?: throw IOException("all IP sources failed")
            if (!publishIfCurrent(first, forProfile, epoch)) throw CancellationException("network changed")
            // Address is already visible. Enrich matching-IP country/quality only.
            if (first.inferred) {
                val full = withTimeoutOrNull(3_000L) { detailed.await() }
                if (full != null && full.ip == first.ip) publishIfCurrent(full, forProfile, epoch)
            }
            cacheFor(forProfile)?.quality ?: first
        } finally { detailed.cancel() }
    }

    suspend fun prefetch(forProfile: Long) {
        if (freshCache(forProfile) == null) {
            try { lookup(forProfile) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w("ip prefetch: ${e.message}") }
        }
    }

    private suspend fun quickAddress(url: String): IpQuality? {
        val json = getJson(url)
        val ip = json.optString("ip")
        if (!validAddress(ip)) return null
        return IpQuality(ip, url.substringAfter("://").substringBefore('/').substringBefore('?'),
            "residential", null, null, false, false, false, false,
            json.optString("country").takeIf { it.isNotBlank() }, true)
    }

    private fun validAddress(ip: String): Boolean = ip.isNotBlank() &&
        (ip.matches(Regex("[0-9]{1,3}(\\\\.[0-9]{1,3}){3}")) || (':' in ip && ip.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }))

''' +s[b:];p.write_text(s)
s=p.read_text();a=s.index('    private fun getJson(url: String): JSONObject {');b=s.index('    // ---- FlClash helpers ----',a)
s=s[:a]+'''    internal suspend fun getJson(url: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code != 200) throw SourceFailure(if (response.code == 429) "rateLimited" else "failed")
                        val json = JSONObject(response.body?.string().orEmpty())
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

''' +s[b:];p.write_text(s)
s=p.read_text().replace('private fun from','private suspend fun from')
s=s.replace('        if (type == null) return null','        if (!validAddress(ip)) return null')
s=s.replace('            ip, source, type, organization, asn, isProxy, isVpn, isTor, isAbuser,','            ip, source, type ?: "residential", organization, asn, isProxy, isVpn, isTor, isAbuser,')
s=s.replace('            country, inferred, elapsedMs','            country, inferred || type == null, elapsedMs')
a=s.index('    private data class Done(')
s=s[:a]+'''    private suspend fun doLookup(): IpQuality = FirstReplyRace.first(listOf(
        { fromIpQuery() }, { fromIpApiIs() }, { fromIpApiCom() },
        { fromIdentMe() }, { fromIpLocate() }, { fromProxyCheck() },
    ), 5_000L) ?: throw IOException("all ip sources failed")
}
''';p.write_text(s)
# Bound all HTTP calls; the winner is independent of non-responsive sources.
s=p.read_text().replace('.connectTimeout(8, TimeUnit.SECONDS)', '.connectTimeout(3, TimeUnit.SECONDS)').replace('.readTimeout(8, TimeUnit.SECONDS)', '.readTimeout(4, TimeUnit.SECONDS)').replace('.callTimeout(8, TimeUnit.SECONDS)', '.callTimeout(5, TimeUnit.SECONDS)');p.write_text(s)
print('PASS: original StatsBar lifecycle and scroll behavior restored; IP text only; cancellable first-result publication')
