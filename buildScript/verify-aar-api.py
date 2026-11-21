#!/usr/bin/env python3
import pathlib,subprocess,sys,tempfile,zipfile
with tempfile.TemporaryDirectory() as tmp:
    jar=pathlib.Path(tmp)/'classes.jar'
    with zipfile.ZipFile(sys.argv[1]) as z: jar.write_bytes(z.read('classes.jar'))
    api=subprocess.check_output(['javap','-classpath',str(jar),'libcore.Libcore','libcore.BoxInstance','libcore.LocalDNSTransport','libcore.BoxPlatformInterface','libcore.NB4AInterface'],text=True)
    for method in ['initCore(','newSingBoxInstance(','urlTest(','urlTestWithFlags(','versionBox(','resetAllConnections(','newHttpClient(','forceGc(','start(','close(','selectOutbound(','setAsMain(','setV2rayStats(','queryStats(']:
        assert method in api, 'Missing original UI JNI method '+method
    out=pathlib.Path('core-audit');out.mkdir(exist_ok=True)
    (out/'aar-api.txt').write_text(api)
    print('AAR includes original pre26 JNI entry points and upgraded URLTest')
