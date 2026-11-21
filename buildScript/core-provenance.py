#!/usr/bin/env python3
import hashlib,json,pathlib,re,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
def git(*args,dir=root):return subprocess.check_output(['git','-C',str(dir),*args],text=True).strip()
core=root.parent/'sing-box'
sha='824dbcfb4dd0a95921840b1737b950cde590241e'
pins=(root/'buildScript/lib/core/get_source_env.sh').read_text()
match=re.search(r'^export COMMIT_SING_BOX="([0-9a-f]{40})"',pins,re.M)
assert match, 'Missing precise core compatibility commit pin'
core_commit=git('rev-parse','HEAD',dir=core)
assert core_commit==match[1], 'Core checkout differs from committed pin'
subprocess.run(['git','-C',str(core),'merge-base','--is-ancestor',sha,core_commit],check=True)
assert not git('status','--porcelain',dir=core),'Core fork has uncommitted changes'
assert git('show',sha+':go.mod',dir=core).startswith('module github.com/sagernet/sing-box'),'Wrong core repository'
# Record the reviewed compatibility tree in addition to its upstream ancestry.
# No unpublished working-tree patch is allowed into the signed candidate.
files={}
for name in ['libcore/go.mod','libcore/husiurltest/urltest.go','libcore/husiurltest/http3.go','buildScript/lib/core/get_source_env.sh','libcore/build.sh']:
    files[name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
for f in sorted((root/'buildScript').rglob('*.patch')):
    files[str(f.relative_to(root))]=hashlib.sha256(f.read_bytes()).hexdigest()
provenance={'baseline_repo':'oh5uosnvh/Neko-UI','baseline_version':'1.4.2-mg-pre26','baseline_commit':'49054c5845a93fb5ab23402d75788d4327c4c85d','app_commit':git('rev-parse','HEAD'),'upstream_repo':'SagerNet/sing-box','upstream_version':'v1.15.0-alpha.13','upstream_commit':sha,'core_commit':core_commit,'core_tree':git('rev-parse','HEAD^{tree}',dir=core),'urltest_reference':'oh5uosnvh/husi@df68c414ee9316ebb87ddac25e085a54b9726914','source_files':files,'core_diff_sha256':hashlib.sha256(git('diff','--binary','HEAD',dir=core).encode()).hexdigest()}
out=root/'app/src/main/assets/core-provenance.json';out.parent.mkdir(exist_ok=True,parents=True);out.write_text(json.dumps(provenance,ensure_ascii=False,indent=2))
print('Recorded real v1.15 core provenance for',provenance['app_commit'])
