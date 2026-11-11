#!/usr/bin/env python3
"""Hermetic tests: digest, idempotence, staged and unknown dirty edits."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent

class PatchTests(unittest.TestCase):
    def test_exact_patch_only(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            core = root / 'core'
            tools = root / 'tools'
            series = tools / 'patches/rule-global-v1'
            series.mkdir(parents=True)
            shutil.copy(HERE / 'apply_patches.py', tools)
            core.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(core), *args], stderr=subprocess.STDOUT).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Patch Test')
            git('config', 'user.email', 'test@example.invalid')
            (core / 'a.txt').write_text('baseline\n')
            git('add', '.')
            git('commit', '-qm', 'baseline')
            baseline = git('rev-parse', 'HEAD')
            patch = '--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-baseline\n+patched\n'
            (series / '0001.patch').write_text(patch)
            manifest = {'baseline': baseline, 'patches': [{'file': '0001.patch', 'sha256': hashlib.sha256(patch.encode()).hexdigest()}]}
            (series / 'series.json').write_text(json.dumps(manifest))
            def apply(ok=True):
                result = subprocess.run(['python3', str(tools / 'apply_patches.py'), str(core)], capture_output=True)
                self.assertEqual(result.returncode == 0, ok, result.stdout.decode() + result.stderr.decode())
            apply()
            apply()
            self.assertEqual((core / 'a.txt').read_text(), 'patched\n')
            (core / 'a.txt').write_text('unknown\n')
            apply(False)
            self.assertEqual((core / 'a.txt').read_text(), 'unknown\n')
            (core / 'a.txt').write_text('patched\n')
            git('add', '.')
            apply()
            (core / 'unknown.go').write_text('package unknown\n')
            apply(False)
            (core / 'unknown.go').unlink()
            manifest['patches'][0]['sha256'] = '0' * 64
            (series / 'series.json').write_text(json.dumps(manifest))
            apply(False)

if __name__ == '__main__':
    unittest.main(verbosity=2)
