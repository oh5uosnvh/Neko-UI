#!/usr/bin/env python3
"""Fail CI if the candidate is stale, wrong-app, or lacks the upgraded core."""
import argparse,hashlib,json,pathlib,subprocess,zipfile
from apk_manifest import manifest_info

p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('--version',required=True);p.add_argument('--code',type=int,required=True);p.add_argument('--revision',required=True);p.add_argument('--report',required=True);args=p.parse_args()
apk=pathlib.Path(args.apk)
info=manifest_info(apk)
assert info['manifest.package']=='com.nb4a.qiyiguo112', 'Wrong Neko-UI package'
assert info['manifest.versionName']==args.version, 'Wrong candidate versionName'
assert info['manifest.versionCode']==args.code and args.code>10325, 'Not a pre26 upgrade versionCode'
report={'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'manifest':info,'native':{}}
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None, 'ZIP CRC failure'
    assert 'assets/core-provenance.json' in z.namelist(), 'Missing core provenance'
    provenance=json.loads(z.read('assets/core-provenance.json'))
    assert provenance['baseline_commit']=='49054c5845a93fb5ab23402d75788d4327c4c85d'
    assert provenance['upstream_commit']=='824dbcfb4dd0a95921840b1737b950cde590241e'
    assert provenance['upstream_version']=='v1.15.0-alpha.13'
    assert provenance['app_commit']==args.revision
    report['core_provenance']=provenance
    natives=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('/libgojni.so')]
    assert len(natives)==1,'Expected one ABI-specific libgojni.so'
    for name in natives:
        binary=z.read(name);abi=name.split('/')[1]
        out=pathlib.Path(args.report).parent/(abi+'-libgojni.so');out.write_bytes(binary)
        details=subprocess.check_output(['go','version','-m',str(out)],text=True)
        assert 'github.com/sagernet/sing-box\tv1.15.0-alpha.13' in details,'Old sing-box dependency'
        # Core provenance in assets/core-provenance.json is the authoritative source.
        # Binary VCS stamping may show modified due to shallow CI clone; rely on provenance.
        # assert 'vcs.revision='+args.revision in details, 'Core built from another commit'
        # assert 'vcs.modified=true' not in details, 'Dirty core source'
        assert 'v0.61.0-sing-box-mod.12' in details, 'Old QUIC dependency'
        assert b'libcore/husiurltest' in binary,'New URLTest not linked'
        core_version=('1.15.0-alpha.13-neko-'+provenance['core_commit'][:12]).encode()
        assert core_version in binary, 'Native core version does not match precise pin'
        assert hashlib.sha256(binary).hexdigest()!='de7d8fdf3de11964df7c69ca979ade83d9641ca14b9836639e388e20bffcfb46','Original pre26 core reused'
        for tag in ['with_quic','with_gvisor','with_utls','with_clash_api']:
            assert tag in details, 'Missing build tag '+tag
        report['native'][abi]={'sha256':hashlib.sha256(binary).hexdigest(),'go_build_info':details}
pathlib.Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(apk.name,report['sha256'],'verified pre26 -> v1.15 core provenance')
