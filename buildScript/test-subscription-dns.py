#!/usr/bin/env python3
"""Run the real SubscriptionDns.java with JUnit; Python 3 + JDK 11+, no Android SDK.

From the repository root:
  python3 buildScript/test-subscription-dns.py
  python3 buildScript/test-subscription-dns.py --cache /path/to/jvm-jars
  python3 buildScript/test-subscription-dns.py --cache /path/to/jvm-jars --offline
  python3 buildScript/test-subscription-dns.py --audit-yaml /private/uploads/*.yaml

Only three jars (762,065 bytes total) are needed. First use verifies Maven Central
SHA-1 sidecars against pinned digests, then verifies every jar. Verified cache
hits perform no downloads. --offline additionally forbids missing-cache fetches.
Compilation output is temporary; no sources are copied/reimplemented or patched.
Optional private YAML audit prints only basenames and host/policy/proxy counts;
policy counts include ordinary plus proxy-server policies. Inputs never become
repository fixtures, snapshots, or logs. Builder/persistence tests run in Gradle.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[1]
PACKAGE = "io.nekohasekai.sagernet.group.SubscriptionDnsTest"
SOURCE = REPO / "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionDns.java"
TEST = REPO / "app/src/test/java/io/nekohasekai/sagernet/group/SubscriptionDnsTest.java"
MAVEN = "https://repo.maven.apache.org/maven2/"
ARTIFACTS = (
    ("org.yaml", "snakeyaml", "2.7", "47713fda569f07641538ff7f2fa9cb3ead4603e5"),
    ("junit", "junit", "4.13.2", "8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12"),
    ("org.hamcrest", "hamcrest-core", "1.3", "42a25dc3219429f0e5d060061f71acb49bf010a0"),
)


def atomic_write(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as f:
        temporary = Path(f.name)
        try:
            f.write(data)
            f.close()
            os.replace(temporary, path)
        finally:
            if temporary.exists():
                temporary.unlink()


def fetch(url, limit):
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(limit + 1)
        if len(data) > limit:
            raise RuntimeError("Dependency response exceeds size limit")
        return data
    except (OSError, urllib.error.URLError) as error:
        raise RuntimeError("Maven download failed for " + url.rsplit("/", 1)[-1]) from error


def digest(data):
    return hashlib.sha1(data).hexdigest()


def dependencies(cache, offline):
    cache.mkdir(parents=True, exist_ok=True)
    jars = []
    for group, artifact, version, pinned in ARTIFACTS:
        name = artifact + "-" + version + ".jar"
        jar = cache / name
        sidecar = cache / (name + ".sha1")
        url = MAVEN + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + name
        if not sidecar.exists():
            if offline:
                raise RuntimeError("Offline cache lacks Maven sidecar: " + sidecar.name)
            value = fetch(url + ".sha1", 256).decode("ascii").strip().lower()
            if not re.fullmatch(r"[0-9a-f]{40}", value) or value != pinned:
                raise RuntimeError("Maven sidecar mismatch: " + name)
            atomic_write(sidecar, (value + "\n").encode("ascii"))
        expected = sidecar.read_text(encoding="ascii").strip().lower()
        if expected != pinned:
            raise RuntimeError("Cached Maven sidecar mismatch: " + name)
        if not jar.exists() or digest(jar.read_bytes()) != expected:
            if offline:
                raise RuntimeError("Offline jar missing or checksum mismatch: " + name)
            print("Download " + name, flush=True)
            data = fetch(url, 900_000)
            if digest(data) != expected:
                raise RuntimeError("Downloaded jar checksum mismatch: " + name)
            atomic_write(jar, data)
        if digest(jar.read_bytes()) != expected:
            raise RuntimeError("Cached jar checksum mismatch: " + name)
        jars.append(jar)
    if sum(jar.stat().st_size for jar in jars) >= 1_000_000:
        raise RuntimeError("Parser test dependencies unexpectedly exceed 1 MB")
    return jars


def main():
    default_cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "neko-subscription-dns"
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cache", type=Path, default=default_cache, help="directory containing verified jars and .sha1 sidecars")
    parser.add_argument("--offline", action="store_true", help="never fetch; require a previously verified cache")
    parser.add_argument("--audit-yaml", type=Path, nargs="+", default=[], metavar="FILE", help="explicit private YAML inputs, read-only and counts-only")
    args = parser.parse_args()
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        parser.error("java and javac are required (JDK 11+, no Android SDK)")
    for source in (SOURCE, TEST):
        if not source.is_file():
            raise RuntimeError("Required source is missing: " + source.name)
    cp = os.pathsep.join(str(jar) for jar in dependencies(args.cache.expanduser().resolve(), args.offline))
    with tempfile.TemporaryDirectory(prefix="neko-subscription-dns-") as output:
        compile_result = subprocess.run(
            [javac, "-encoding", "UTF-8", "--release", "8", "-cp", cp, "-d", output, str(SOURCE), str(TEST)],
            timeout=120, check=False)
        if compile_result.returncode:
            return 1
        command = [java, "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", output + os.pathsep + cp]
        tests = subprocess.run(command + ["org.junit.runner.JUnitCore", PACKAGE], timeout=120, check=False)
        audit_failed = False
        # Still audit explicit local inputs if a regression fails; never hide the nonzero test result.
        if args.audit_yaml:
            audit = subprocess.run(command + [PACKAGE] + [str(p.expanduser().resolve()) for p in args.audit_yaml],
                                   timeout=120, check=False)
            audit_failed = audit.returncode != 0
        return 1 if tests.returncode or audit_failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.TimeoutExpired:
        print("ERROR: JVM compile/test/audit exceeded 120 seconds", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, OSError, UnicodeError) as error:
        # Do not print subprocess arguments (which can include private input paths).
        print("ERROR: " + str(error) if isinstance(error, RuntimeError) else "ERROR: dependency or filesystem operation failed", file=sys.stderr)
        sys.exit(1)
