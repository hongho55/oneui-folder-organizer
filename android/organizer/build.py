"""Build the offline folder editor + optional Shizuku file bridge (JDK 21, SDK 35/36)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

BASE = Path(__file__).resolve().parent
DEPS = {
    'api': '4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b',
    'aidl': '33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963',
    'shared': '4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635',
    'provider': 'b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Set ANDROID_HOME or supply --sdk')
    sdk = Path(args.sdk).expanduser().resolve()
    tools = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-35'
    if args.java_home:
        java = Path(args.java_home).expanduser().resolve() / 'bin'
    else:
        compiler = shutil.which('javac')
        if not compiler:
            parser.error('A JDK is required; set JAVA_HOME')
        java = Path(compiler).resolve().parent
    if not (platform / 'android.jar').is_file() or not tools.is_dir():
        parser.error("Install SDK platforms;android-35 and build-tools;36.0.0")
    out = BASE / 'build'
    out.mkdir(exist_ok=True)
    for folder in ['classes', 'dex', 'generated']:
        generated = out / folder
        if generated.exists():
            shutil.rmtree(generated)
        generated.mkdir()
    (out / 'deps').mkdir(exist_ok=True)
    env = dict(os.environ)
    if args.java_home:
        env['JAVA_HOME'] = str(Path(args.java_home).resolve())

    def run(*command, capture=False):
        print('RUN', Path(str(command[0])).name, flush=True)
        return subprocess.run([str(x) for x in command], cwd=BASE, check=True, timeout=180,
                              env=env, capture_output=capture, text=capture)

    jars = []
    for artifact, expected in DEPS.items():
        name = artifact + '-13.1.5'
        aar = out / 'deps' / (name + '.aar')
        if not aar.exists():
            url = 'https://repo.maven.apache.org/maven2/dev/rikka/shizuku/' + artifact + '/13.1.5/' + name + '.aar'
            with urllib.request.urlopen(url, timeout=45) as response:
                data = response.read(1024 * 1024)
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError('Dependency hash mismatch: ' + artifact)
            aar.write_bytes(data)
        if hashlib.sha256(aar.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Cached dependency hash mismatch: ' + artifact)
        jar = out / 'deps' / (name + '.jar')
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read('classes.jar'))
        jars.append(jar)
    android = platform / 'android.jar'
    manifest_text = (BASE / 'AndroidManifest.xml').read_text(encoding='utf-8').replace('${applicationId}', 'dev.local.oneuifolders')
    manifest = ET.fromstring(manifest_text)
    ns = '{http://schemas.android.com/apk/res/android}'
    permissions = [p.get(ns + 'name') for p in manifest.findall('uses-permission')]
    if permissions != ['moe.shizuku.manager.permission.API_V23']:
        raise RuntimeError('Unexpected Android permissions: ' + str(permissions))
    (out / 'AndroidManifest.xml').write_text(manifest_text, encoding='utf-8')
    run(tools / 'aapt2', 'link', '--manifest', out / 'AndroidManifest.xml', '-I', android, '-o', out / 'resources.apk')
    for aidl in sorted((BASE / 'aidl').rglob('*.aidl')):
        run(tools / 'aidl', '-p' + str(platform / 'framework.aidl'), '-I' + str(BASE / 'aidl'), '-o' + str(out / 'generated'), aidl)
    source = sorted((BASE / 'src').rglob('*.java')) + sorted((out / 'generated').rglob('*.java'))
    run(java / 'javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, [android] + jars)), '-d', out / 'classes', *source)
    run(tools / 'd8', '--lib', android, '--min-api', '30', '--output', out / 'dex', *sorted((out / 'classes').rglob('*.class')), *jars)
    unsigned = out / 'unsigned.apk'
    unsigned.write_bytes((out / 'resources.apk').read_bytes())
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(out / 'dex/classes.dex', 'classes.dex')
        apk.write(BASE / 'THIRD_PARTY_NOTICES.md', 'assets/THIRD_PARTY_NOTICES.txt')
    run(tools / 'zipalign', '-f', '4', unsigned, out / 'aligned.apk')
    key = out / 'local-debug.keystore'
    if not key.exists():
        run(java / 'keytool', '-genkeypair', '-keystore', key, '-alias', 'localdebug', '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=One UI Folder Editor Preview')
    final = out / 'oneui-folder-organizer.apk'
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'localdebug', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', final, out / 'aligned.apk')
    run(tools / 'apksigner', 'verify', '--verbose', final)
    dump = run(tools / 'aapt2', 'dump', 'permissions', final, capture=True).stdout
    if 'android.permission.INTERNET' in dump or 'MANAGE_EXTERNAL_STORAGE' in dump:
        raise RuntimeError('Unexpected network or broad storage permission')
    with zipfile.ZipFile(final) as apk:
        if any(name.endswith(('.exml', '.json', '.keystore')) for name in apk.namelist()):
            raise RuntimeError('A private data file must never be packaged')
    result = {'apk': final.name, 'sha256': hashlib.sha256(final.read_bytes()).hexdigest(),
              'size_bytes': final.stat().st_size, 'permissions': permissions,
              'dependencies': {'Shizuku-API': '13.1.5 (MIT)'},
              'network_permission': False, 'native_launcher_restore_capability': False,
              'signing': 'local debug key; preview, not production'}
    (out / 'build-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
