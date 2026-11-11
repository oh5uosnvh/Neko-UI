#!/usr/bin/env python3
"""Apply the pinned series once, or verify the exact expected patched tree.

Never reset, stash, clean, or overwrite an unrecognized dependency edit.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SERIES = HERE / 'patches/rule-global-v1/series.json'


def run(directory, *args, env=None, check=True):
    return subprocess.run(['git', '-C', str(directory), *args], env=env,
                          check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE)


def apply(directory, check_only=False):
    directory = Path(directory).resolve()
    manifest = json.loads(SERIES.read_text())
    baseline = manifest['baseline']
    patches = []
    for item in manifest['patches']:
        path = SERIES.parent / item['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise RuntimeError('Patch digest mismatch: ' + str(path))
        patches.append(path)
    head = run(directory, 'rev-parse', 'HEAD').stdout.decode().strip()
    if head != baseline:
        raise RuntimeError(f'Expected core baseline {baseline}, got {head}')
    with tempfile.TemporaryDirectory(prefix='nekobox-core-patch-') as tmp:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(tmp) / 'index'))
        run(directory, 'read-tree', baseline, env=env)
        for path in patches:
            run(directory, 'apply', '--cached', '--whitespace=error', str(path), env=env)
        expected = run(directory, 'write-tree', env=env).stdout.decode().strip()
    # Untracked/ignored files can affect a Go build too: refuse all unknown ones.
    unknown = run(directory, 'ls-files', '--others').stdout.decode().splitlines()
    if unknown:
        raise RuntimeError('Unknown untracked dependency files: ' + ', '.join(unknown[:8]))
    index = run(directory, 'write-tree').stdout.decode().strip()
    base_tree = run(directory, 'rev-parse', baseline + '^{tree}').stdout.decode().strip()
    if index not in (base_tree, expected):
        raise RuntimeError('Unknown staged dependency edits; refusing to overwrite')
    if run(directory, 'diff', '--quiet', expected, '--', check=False).returncode == 0:
        print('Core patch series verified: rule-global-v1 ' + expected)
        return
    clean = run(directory, 'status', '--porcelain').stdout == b''
    if check_only or not clean:
        raise RuntimeError('Dependency differs from the exact checked-in patch series; refusing to overwrite')
    for path in patches:
        run(directory, 'apply', '--check', str(path))
        run(directory, 'apply', '--whitespace=error', str(path))
    if run(directory, 'diff', '--quiet', expected, '--', check=False).returncode:
        raise RuntimeError('Patched core does not match expected tree')
    print('Core patch series applied: rule-global-v1 ' + expected)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    try:
        apply(args.directory, args.check_only)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
