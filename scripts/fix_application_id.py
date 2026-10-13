#!/usr/bin/env python3
"""Pin release application ID without changing source namespace."""
from pathlib import Path
import re
import sys

APPLICATION_ID = "com.nb4a.qiyiguo112"
root = Path(sys.argv[1])
p = root / "nb4a.properties"
text = p.read_text()
text, count = re.subn(r"(?m)^PACKAGE_NAME\s*=.*$", "PACKAGE_NAME=" + APPLICATION_ID, text)
if count != 1:
    raise SystemExit("Expected exactly one PACKAGE_NAME in nb4a.properties")
p.write_text(text)
print("Pinned release application ID:", APPLICATION_ID)
