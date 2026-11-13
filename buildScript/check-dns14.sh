#!/usr/bin/env bash
# Run by the controlling CI, never implicitly by the local editor.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
export TMPDIR="${TMPDIR:-$HOME/.hermes/cache/scratch}"
mkdir -p "$TMPDIR"
fixtures="$(mktemp -d "$TMPDIR/neko-dns14.XXXXXX")"
trap 'rm -rf "$fixtures"' EXIT
./gradlew :app:testPreviewDebugUnitTest \
  --tests io.nekohasekai.sagernet.group.GlobalSelectorBuildTest \
  --tests io.nekohasekai.sagernet.group.SubscriptionDnsBuilderTest \
  --tests io.nekohasekai.sagernet.fmt.SelectorDnsPolicyTest \
  -Dneko.dns.fixtureDir="$fixtures" \
  --rerun-tasks --no-daemon --max-workers=1 -x :app:buildHevTun
# Missing exports fail, rather than silently passing a skipped decoder test.
for name in forTest runtime-fakeip subscription-selector-tcp-tls block-predefined runtime-local subscription-single-hosts; do
  test -s "$fixtures/$name.json"
done
cd libcore
NEKO_DNS_FIXTURE_DIR="$fixtures" GOMAXPROCS=1 \
  go test -p 1 ./dnspolicy -run 'TestDNS14|TestConfigBuilderDNS14Fixtures' -count=1 -v
