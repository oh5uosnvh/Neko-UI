#!/usr/bin/env python3
"""Require exactly the audited legacy signer in apksigner --print-certs output."""
import re
import sys
from pathlib import Path

expected = "521ea638c17fdee184998b4a3cf9b10fa6272b9806fae34b19ca3fffe5d4556a"
text = Path(sys.argv[1]).read_text(encoding="utf-8")
certs = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$", text, re.M)
if len(certs) != 1 or certs[0][0] != "1" or certs[0][1].replace(":", "").lower() != expected:
    sys.exit("APK signing certificate mismatch or ambiguous signer count")
print("APK legacy signing certificate verified")
