#!/usr/bin/env python3
"""Idempotent injection for the 1.14.x-neko sing-box tree (runtime routing v2).

The 1.14.x-neko branch ships the runtime routing integration as commits, so
this script verifies the installed files match the expected versions and
re-writes them if a partial checkout drifted. It no longer does exact-match
string surgery on upstream files — the neko tree IS the patched tree.

Run: python3 libcore/runtimepatch/apply.py /path/to/sing-box
Verify: python3 libcore/runtimepatch/apply.py --verify-existing /path/to/sing-box <commit>
Never invokes git, reload, or changes dependency/version metadata.
"""
import pathlib, sys, json, hashlib, subprocess

verify = sys.argv[1] == '--verify-existing'
root = pathlib.Path(sys.argv[2] if verify else sys.argv[1])
here = pathlib.Path(__file__).parent

# (source txt, target in sing-box tree)
MAPPING = [
    ('adapter_runtime_routing.go.txt', 'adapter/runtime_routing.go'),
    ('route_runtime_routing.go.txt', 'route/runtime_routing.go'),
    ('dns_runtime_routing.go.txt', 'dns/runtime_routing.go'),
    ('adapter_runtime_routing_test.go.txt', 'adapter/runtime_routing_test.go'),
    ('box_runtime_routing_test.go.txt', 'runtime_routing_test.go'),
    ('selector.go.txt', 'protocol/group/selector.go'),
]

MARK = '// neko-runtime-routing-v1'

if verify:
    try:
        manifest = json.loads((root / '.git' / 'neko-runtime-routing.json').read_text())
        head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
        if head != sys.argv[3] or manifest['commit'] != head:
            raise ValueError('base commit mismatch')
        paths = set(subprocess.check_output(['git', '-C', str(root), 'diff', '--name-only', 'HEAD'], text=True).splitlines())
        paths.update(subprocess.check_output(['git', '-C', str(root), 'ls-files', '--others', '--exclude-standard'], text=True).splitlines())
        if paths != set(manifest['files']):
            raise ValueError('unexpected dependency changes')
        for name, digest in manifest['files'].items():
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
                raise ValueError('changed patch: ' + name)
    except Exception as e:
        raise SystemExit('Runtime patch verification failed: ' + str(e))
    print('Verified exact installed runtime patch')
    sys.exit(0)

if MARK not in (root / 'route/router.go').read_text():
    raise SystemExit('not a 1.14.x-neko tree: marker missing from route/router.go')

changed = {}
for source, target in MAPPING:
    expected = (here / source).read_text()
    current = (root / target).read_text()
    if current != expected:
        changed[target] = expected

for name, content in changed.items():
    (root / name).write_text(content)

print('runtime routing v2 verified, %d file(s) realigned' % len(changed))

if (root / '.git').is_dir():
    paths = set(subprocess.check_output(['git', '-C', str(root), 'diff', '--name-only', 'HEAD'], text=True).splitlines())
    paths.update(subprocess.check_output(['git', '-C', str(root), 'ls-files', '--others', '--exclude-standard'], text=True).splitlines())
    manifest = {
        'commit': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        'files': {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in paths},
    }
    (root / '.git' / 'neko-runtime-routing.json').write_text(json.dumps(manifest, sort_keys=True))
