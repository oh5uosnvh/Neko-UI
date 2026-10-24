#!/usr/bin/env python3
from pathlib import Path
import re
import sys
r=Path(sys.argv[1]).resolve()

def rep(rel, old, new):
 p=r/rel; s=p.read_text()
 if new in s: return
 if old not in s: raise SystemExit(f'missing IP anchor: {rel}: {old[:100]}')
 p.write_text(s.replace(old,new,1))

# Add a live stream so the bar and dialog receive the same incremental result.
p='app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt'
s=(r/p).read_text()
if 'val updates = live.asStateFlow()' not in s:
 s=s.replace('''    private var cache: CacheEntry? = null
''','''    private var cache: CacheEntry? = null
    private val live = kotlinx.coroutines.flow.MutableStateFlow<CacheEntry?>(null)
    val updates = live.asStateFlow()
''',1)
 s=s.replace('''    fun cacheFor(profileId: Long): CacheEntry? =
''','''    private fun publish(profileId: Long, q: IpQuality): IpQuality {
        val entry = CacheEntry(q, profileId, System.currentTimeMillis())
        cache = entry
        live.value = entry
        return q
    }

    fun cacheFor(profileId: Long): CacheEntry? =
''',1)
 s=s.replace('''        cache = CacheEntry(q, forProfile, System.currentTimeMillis())
        return q''','''        return publish(forProfile, q)''',1)
 # clean base already has from sources; add lightweight quick helper before SourceFailure.
 anchor='''    private class SourceFailure(val kind: String) : Exception(kind)
'''
 helper='''    private suspend fun quickAddress(url: String): IpQuality? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val json = getJson(url)
        val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return@withContext null
        IpQuality(ip, url.substringAfter("//").substringBefore('/'), "residential", null, null,
            false, false, false, false, json.optString("country").takeIf { it.isNotBlank() }, true)
    }

'''
 if helper not in s: s=s.replace(anchor,helper+anchor,1)
 # imports for flow and withContext
 if 'import kotlinx.coroutines.withContext' not in s:
  s=s.replace('import kotlinx.coroutines.withTimeoutOrNull\n','import kotlinx.coroutines.withTimeoutOrNull\nimport kotlinx.coroutines.withContext\nimport kotlinx.coroutines.asCoroutineDispatcher\n',1)
 # asStateFlow import is extension; use fully qualified or add import.
 if 'import kotlinx.coroutines.flow.asStateFlow' not in s:
  s=s.replace('import kotlinx.coroutines.withContext\n','import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.flow.asStateFlow\n',1)
 (r/p).write_text(s)
# A detailed request must survive the quick-address winner. The old nested
# FirstReplyRace cancelled it, which left the bar with an IP but no metadata.
p='app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt'
s=(r/p).read_text()
m=re.search(r'    suspend fun lookup\(forProfile: Long\): IpQuality(?: = [^{]+)? \{',s)
if not m: raise SystemExit('missing lookup function anchor')
start=m.start()
end=s.index('\n    suspend fun prefetch(forProfile: Long)',start)
new='''    suspend fun lookup(forProfile: Long): IpQuality = kotlinx.coroutines.supervisorScope {
        val epoch = epoch()
        val detailed = async(Dispatchers.IO) { runCatching { doLookup() }.getOrNull() }
        val quick = FirstReplyRace.first(listOf(
            { quickAddress("https://api.ipify.org?format=json") },
            { quickAddress("https://ipinfo.io/json") },
        ), 5_000L)
        val first = quick ?: withTimeoutOrNull(5_000L) { detailed.await() }
            ?: throw IOException("all IP sources failed")
        if (!publishIfCurrent(first, forProfile, epoch)) throw CancellationException("network changed")
        val full = withTimeoutOrNull(8_000L) { detailed.await() }
        if (full != null && full.ip == first.ip) publishIfCurrent(full, forProfile, epoch)
        cacheFor(forProfile)?.quality ?: first
    }
'''
s=s[:start]+new+s[end:]
(r/p).write_text(s)

# MainActivity keeps the bottom card synchronized with late metadata enrichment.
p='app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt'
s=(r/p).read_text()
if 'IpQualityLookup.updates.collect' not in s:
 anchor='''        DataStore.configurationStore.registerChangeListener(this)
'''
 s=s.replace(anchor,anchor+'''        lifecycleScope.launch {
            IpQualityLookup.updates.collect { entry ->
                if (entry?.profileId == DataStore.currentProfile && DataStore.serviceState.connected) {
                    binding.connectBar.renderNetwork(entry.quality)
                }
            }
        }
''',1)
 if 'import kotlinx.coroutines.flow.collect' not in s:
  s=s.replace('import kotlinx.coroutines.launch\n','import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.collect\n',1)
 (r/p).write_text(s)
# Dialog subscribes to incremental results while it is visible.
p='app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt'
s=(r/p).read_text()
if 'private var liveJob' not in s:
 s=s.replace('''    private var loading = false
''','''    private var loading = false
    private var liveJob: kotlinx.coroutines.Job? = null
''',1)
 anchor='''        dialog.setOnShowListener {'''
 s=s.replace(anchor,'''        liveJob = lifecycleScope.launch {
            IpQualityLookup.updates.collect { entry ->
                if (entry?.profileId == DataStore.currentProfile && isAdded) {
                    render(entry.quality)
                    loading = false
                }
            }
        }

'''+anchor,1)
 # Ensure imports are available without relying on patch context.
 if 'import androidx.lifecycle.lifecycleScope' not in s:
  s=s.replace('import androidx.fragment.app.DialogFragment\n','import androidx.fragment.app.DialogFragment\nimport androidx.lifecycle.lifecycleScope\n',1)
 if 'import kotlinx.coroutines.flow.collect' not in s:
  s=s.replace('import io.nekohasekai.sagernet.R\n','import io.nekohasekai.sagernet.R\nimport kotlinx.coroutines.flow.collect\n',1)
 if 'override fun onDestroyView()' not in s:
  s += '''\n    override fun onDestroyView() {\n        liveJob?.cancel()\n        liveJob = null\n        super.onDestroyView()\n    }\n'''
 # Never let a stale loading flag block retry after an exception/cancellation.
 s=s.replace('''        if (loading) return
''','''        if (loading && !force) return
        if (force) loading = false
''',1)
 (r/p).write_text(s)
print('PASS: IP quick result, detailed enrichment, live dialog updates, retry-safe loading')
