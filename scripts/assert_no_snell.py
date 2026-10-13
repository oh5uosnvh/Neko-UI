from pathlib import Path
import sys
r=Path(sys.argv[1])
for p in [r/'.github/workflows/build.yml']:
    s=p.read_text()
    assert 'repair_snell.py' not in s
    assert 'snell-yf' not in s
print('PASS: final Neko build excludes abandoned Snell/OIX path')
