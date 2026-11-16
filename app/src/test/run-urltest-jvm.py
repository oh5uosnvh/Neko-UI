#!/usr/bin/env python3
"""Run Android URLTest's pure Kotlin tests on Linux/aarch64 without Android/JNI.

Requires JDK + Python 3 and the project's Gradle 8.10.2/coroutines 1.6.4 caches
(populated by an ordinary Gradle build). Does not download or build native code.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))


def cached(pattern):
    matches = sorted(gradle_home.glob(pattern))
    if not matches:
        raise SystemExit("Missing cached dependency: " + pattern + "; run a Gradle build first")
    return matches[0]


gradle = cached("wrapper/dists/gradle-8.10.2-bin/*/gradle-8.10.2/lib")
coroutines = cached("caches/modules-2/files-2.1/org.jetbrains.kotlinx/"
                    "kotlinx-coroutines-core-jvm/1.6.4/*/*.jar")
classpath = os.pathsep.join(map(str, sorted(gradle.glob("*.jar")) + [coroutines]))
package = "io/nekohasekai/sagernet/bg/proto"
sources = root / "app/src/main/java" / package
tests = root / "app/src/test/java" / package
classes = ["UrlTestLifecycleTest", "UrlTestTempFilesTest", "UrlTestSchedulingContractTest"]
files = [sources / "UrlTestLifecycle.kt", sources / "UrlTestTempFiles.kt"]
files += [tests / (name + ".kt") for name in classes]
with tempfile.TemporaryDirectory(prefix="android-urltest-jvm-") as output:
    subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", output]
                   + list(map(str, files)), cwd=root, check=True)
    subprocess.run(["java", "-cp", output + os.pathsep + classpath, "org.junit.runner.JUnitCore"]
                   + [package.replace("/", ".") + "." + name for name in classes],
                   cwd=root, check=True)
