#!/usr/bin/env python3
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
kit=Path(__file__).resolve().parents[1]
shutil.copytree(kit/'files/oppa-yaml',root/'app/src',dirs_exist_ok=True)
p=root/'app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt'
s=p.read_text()
anchor='                        "snell" -> {'
branch='''                        "oppa" -> {
                            proxies.add(io.nekohasekai.sagernet.fmt.oppa.parseClashOppa(proxy))
                        }

'''
if branch not in s:
    assert s.count(anchor)==1,'OPPA YAML parser anchor mismatch'
    s=s.replace(anchor,branch+anchor,1)
p.write_text(s)
print('PASS: OPPA YAML uses the shared raw/file/clipboard/subscription importer')
