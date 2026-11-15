# URLTest batch performance candidate — 1.4.2-mg-2611152340

Scope: disposable batch URLTest only. Protocol implementations, normal VPN startup/control-bar probing, and the previous VPN-off socket-protection fix are unchanged. No release tag is created.

## Confirmed issues

1. `ConfigurationFragment.urlTest` silently clamped the configured concurrency to16. A setting of36 therefore used only16 workers. The batch now honors the configured concurrency, bounded by the number of selected nodes and a minimum of1 for nonempty batches.
2. `probeHTTP` made two sequential real GET requests under one user deadline, then returned only the second request's WroteHeaders-to-first-byte interval. A successful first response could be discarded when the hidden second request exceeded the remaining budget. A deterministic cold-dial110ms + response90ms regression under270ms fails before this patch and succeeds after it.
3. `UrlTest.doTest` rebuilt the whole temporary core on every exception, with250ms backoff. A dead node could consume two full HTTP budgets plus two startup/teardown cycles. Batch probing now uses one started core and one request; explicit retests remain available. Startup/control-bar retry policy is unchanged.
4. The generic proxy HTTP transport had fixed3s TLS/header timers. Disposable probe transports now clone it and rely on the configured end-to-end request deadline rather than those hidden caps.

## Measurement contract

- One real GET per node, with redirects not followed, and response bodies not downloaded.
- Configured HTTP timeout is still one total request budget: DNS, connection establishment, proxy/TLS handshake and response headers must fit. It does not include waiting in the batch queue or core construction (which has its existing separate setup watchdog).
- Displayed latency retains the existing WroteHeaders-to-first-response-byte metric. It is not total elapsed time and is not ICMP ping. Unlike the old warmed second sample, it is now the first successful request; protocols doing lazy handshakes on first write may report a larger, real cold-sample latency. No subtraction, fake response, cached RTT, or enlarged timeout is used.
- A400ms displayed RTT does not prove that cold DNS/dial/handshake completed inside3000ms. The redundant second request and silent worker cap are fixed, but true network timeouts remain timeouts.

## Regression coverage

- Successful first response is not discarded by hidden second request.
- Cold connection remains bounded by the user's deadline.
- Generic response-header cap cannot override the user budget.
- Large response bodies do not stall latency measurement; redirects do not add requests.
- Lazy first-write cost remains visible, not artificially removed.
-94 requests with36 HTTP workers reach peak concurrency36 and perform exactly94GETs.
- Kotlin policy honors36/94, bounds excess workers to node count, handles empty/invalid settings; actual36 blocking probe workers enter together.
- Existing cancellation/cleanup tests (including cancellation while native initialization blocks) remain in force.

Local Kotlin JUnit:10 tests passed. HTTP regressions:5 consecutive runs passed. Initial all-probe run completed one full pass, but repeated full-core tests in Android PRoot hit a host `testing.Chdir`/bad-file-descriptor failure; full factory/cache regression is therefore also required on Linux CI. Synthetic loopback timings are not subscription or Android UI performance claims. Real subscription/network results, if obtained, must be reported separately and without credentials.
