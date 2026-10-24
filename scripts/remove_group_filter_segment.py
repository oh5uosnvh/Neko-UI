#!/usr/bin/env python3
from pathlib import Path
import re,sys
p=Path(sys.argv[1]).resolve()/'app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt';s=p.read_text()
s=s.replace('        setupFilterBar()\n','',1)
s=s.replace('    private lateinit var filterDefaultCell: TextView\n','').replace('    private lateinit var filterSortCell: TextView\n','')
# Remove the toolbar segment construction, text update, and picker UI; the
# underlying group ordering remains available through normal group operations.
for a,b in [('    // ------------------------------------------------------ 顶栏：过滤/排序\n','    private fun toolbarForeground()'),('    private fun updateFilterBar()','    private fun showFilterPicker')]:
 i=s.find(a)
 if i>=0:
  j=s.find(b,i)
  if j<0: raise SystemExit('segment end missing')
  s=s[:i]+s[j:]
# Remove orphaned picker methods as well.
i=s.find('    private fun showFilterPicker')
if i>=0:
 j=s.find('    private fun pickerContent',i)
 if j<0: raise SystemExit('picker end missing')
 s=s[:i]+s[j:]
s=s.replace('        updateFilterBar()\n','')
p.write_text(s);print('PASS: group default/sort pill removed from source')
