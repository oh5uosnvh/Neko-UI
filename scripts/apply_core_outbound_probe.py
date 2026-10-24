#!/usr/bin/env python3
from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]).resolve();root=Path(__file__).resolve().parents[1]/'files/core-probe/libcore'
for f in root.iterdir(): shutil.copy2(f,r/'libcore'/f.name)
print('PASS: core outbound IP probe installed')
