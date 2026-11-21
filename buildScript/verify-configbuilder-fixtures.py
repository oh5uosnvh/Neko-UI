#!/usr/bin/env python3
"""Fail-closed App ConfigBuilder fixture preflight; --run-go is for CI, not local builds."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

TARGET = "v1.15.0-alpha.13"
SCENARIOS = (
    "hev-ipv4-sniff-off", "hev-dual-sniff-on", "hev-ipv6-sniff-override-resolve",
    "native-ipv4-system-sniff-off", "native-dual-mixed-sniff-on-resolve", "native-ipv6-gvisor-sniff-override",
    "hev-fakeip", "hev-local-dns-hosts", "hev-domain-bootstrap-dns",
    "hev-subscription-single", "hev-subscription-selector", "hev-custom-merge",
)
SOURCES = (
    "app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt",
    "app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt",
    "app/src/main/java/io/nekohasekai/sagernet/database/DefaultRouteCatalog.kt",
    "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionDnsBuilder.kt",
    "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionDns.java",
    "app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java",
    "app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt",
)
TAGS = "with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def read_json(path):
    require(path.is_file() and path.stat().st_size > 0, f"Required fixture missing/empty: {path}")
    data = path.read_bytes()
    obj = json.loads(data)
    require(isinstance(obj, dict) and obj, f"Required fixture not a nonempty JSON object: {path}")
    return data, obj


def verify(directory, repo):
    _, manifest = read_json(directory / "manifest.json")
    require(manifest.get("schema_version") == 1, "Wrong fixture manifest schema")
    require(manifest.get("target_sing_box") == TARGET, "Wrong target kernel version")
    require(manifest.get("producer") == "ConfigBuilderFixtureExportTest/buildConfig(profile,false|true)",
            "Not a real ConfigBuilder exporter manifest")
    hashes = manifest.get("source_sha256", {})
    require(set(hashes) == set(SOURCES), "Missing/extra production source provenance")
    for name in SOURCES:
        source = repo / name
        require(source.is_file(), f"Missing producer source {source}")
        require(hashlib.sha256(source.read_bytes()).hexdigest() == hashes[name],
                f"Stale fixture: producer source changed: {name}")
    expected = {f"{s}-{mode}.json" for s in SCENARIOS for mode in ("production", "urltest")}
    entries = manifest.get("fixtures", [])
    names = [entry.get("name") for entry in entries]
    require(len(names) == len(expected) and set(names) == expected, "Exactly 24 required App fixtures must be exported")
    for entry in entries:
        name = entry["name"]
        original, config = read_json(directory / name)
        require(len(original) == entry.get("bytes") and hashlib.sha256(original).hexdigest() == entry.get("sha256"),
                f"Fixture bytes differ from final ConfigBuilder export: {name}")
        for_test = name.endswith("-urltest.json")
        native = name.startswith("native-") and not for_test
        require(entry.get("for_test") == for_test and entry.get("host_lifecycle") == (not native)
                and entry.get("device_tun_required") == native, f"Lifecycle gate mislabeled: {name}")
        require(all(k in config for k in ("log", "dns", "inbounds", "outbounds", "route")),
                f"Not a full ConfigBuilder JSON: {name}")
        require(config["outbounds"] and isinstance(config["outbounds"], list), f"No production outbounds: {name}")
        inbound_types = {it["type"] for it in config["inbounds"]}
        require(("tun" in inbound_types) == native and ("mixed" in inbound_types) == (not for_test),
                f"Inbounds lost or projected: {name}")
        route = config["route"]
        if not for_test:
            require("experimental" in config and "cache_file" in config["experimental"], f"Experimental config lost: {name}")
            sets = {item["tag"]: item for item in route.get("rule_set", [])}
            refs = {tag for rule in route.get("rules", []) for tag in rule.get("rule_set", [])}
            for tag in ("geosite:cn", "geoip:cn", "geosite:category-ads-all"):
                require(tag in sets and sets[tag].get("path") == tag and tag in refs, f"Default geo loader coverage absent: {name}/{tag}")
        print(f"PREFLIGHT {name}: original_sha256={entry['sha256']} host_lifecycle={not native}")
    print("PREFLIGHT ONLY: 24 nonempty complete exports; NOT evidence of kernel decode/Start or Android TUN")


def kernel_source(repo):
    text = (repo / "libcore/go.mod").read_text()
    match = re.search(r"^replace\s+github\.com/sagernet/sing-box\s+=>\s+(\S+)\s*$", text, re.M)
    require(match is not None, "CI must use a pinned local target sing-box checkout")
    source = (repo / "libcore" / match[1]).resolve()
    require((source / "go.mod").is_file(), f"Target kernel checkout missing: {source}")
    # Private forks may not retain upstream tags; require the exact alpha.13 base commit.
    expected_base = "824dbcfb4dd0a95921840b1737b950cde590241e"
    env_file = (repo / "buildScript/lib/core/get_source_env.sh").read_text()
    pin = re.search(r"^export\s+SING_BOX_UPSTREAM_COMMIT=[\"']?([0-9a-f]{40})", env_file, re.M)
    target_sha = pin[1] if pin else expected_base
    require(target_sha == expected_base, "Fixture target differs from v1.15.0-alpha.13 upstream SHA")
    subprocess.run(["git", "-C", str(source), "merge-base", "--is-ancestor", target_sha, "HEAD"], check=True)
    print(f"KERNEL target_base={TARGET}/{target_sha} checkout={source}")
    return source


def go_gate(repo, directory, tests, tags):
    pattern = "^(" + "|".join(tests) + ")$"
    command = ["go", "test", "-json", "-mod=readonly", "-p", "1", "-count=1", "-timeout=5m", "-tags=" + tags, "-run", pattern, "."]
    environment = os.environ.copy()
    environment["NEKO_CONFIG_FIXTURE_DIR"] = str(directory)
    environment.setdefault("GOMAXPROCS", "2")
    environment.setdefault("GOMEMLIMIT", "700MiB")
    print("CI GATE:", " ".join(command), flush=True)
    completed = subprocess.run(command, cwd=repo / "libcore", env=environment, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(completed.stdout, end="", flush=True)
    if completed.returncode:
        raise SystemExit(completed.returncode)
    events = []
    for line in completed.stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    require(not any(e.get("Action") == "skip" for e in events), "Skipped required test is NOT acceptance")
    passed = {e.get("Test") for e in events if e.get("Action") == "pass"}
    require(set(tests).issubset(passed), "Go returned success without all required tests (zero-match/cache is NOT acceptance)")
    if tests == ["TestPre26AppConfigFixtures"]:
        phases = ("FullOriginalDecode", "StrictDecoderControls", "FullOriginalHostLifecycle", "GeoResourceErrors")
        require(all("TestPre26AppConfigFixtures/" + phase in passed for phase in phases),
                "All four configuration acceptance phases must execute and pass")
        for scenario in SCENARIOS:
            for mode in ("production", "urltest"):
                name = f"{scenario}-{mode}.json"
                for phase in ("FullOriginalDecode", "FullOriginalHostLifecycle"):
                    require(f"TestPre26AppConfigFixtures/{phase}/{name}" in passed,
                            f"Required configuration subtest did not pass: {phase}/{name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-dir", required=True, type=Path)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--run-go", action="store_true", help="CI only; execute real original-byte decode then lifecycle gates")
    parser.add_argument("--tags", default=TAGS, help="Must match final production libcore build tags")
    args = parser.parse_args()
    directory, repo = args.fixture_dir.resolve(), args.repo.resolve()
    verify(directory, repo)
    if args.run_go:
        kernel_source(repo)
        go_gate(repo, directory, ["TestPre26AppConfigFixtures"], args.tags)
        print("HOST GATES passed with explicit simulated platform: 24 full decodes; 21 unmodified full-config lifecycles; Android VPN/protect/network switch, real APK databases, live protocol handshakes and 3 native TUN Start gates remain UNTESTED")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
