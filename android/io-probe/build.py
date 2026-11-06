"""Build the permission-free IO probe using a local Android SDK/JDK; no Gradle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

BASE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Set ANDROID_HOME or supply --sdk')
    sdk = Path(args.sdk).expanduser().resolve()
    tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    if args.java_home:
        java = Path(args.java_home).expanduser().resolve() / 'bin'
    else:
        compiler = shutil.which('javac')
        if not compiler:
            parser.error('A JDK is required; set JAVA_HOME')
        java = Path(compiler).resolve().parent
    if not android.is_file() or not tools.is_dir():
        parser.error("Install SDK platforms;android-35 and build-tools;36.0.0")
    out = BASE / 'build'
    out.mkdir(exist_ok=True)
    for folder in ['classes', 'dex']:
        (out / folder).mkdir(exist_ok=True)
    env = dict(os.environ)
    if args.java_home:
        env['JAVA_HOME'] = str(Path(args.java_home).resolve())

    def run(*command, capture=False):
        print('RUN', Path(str(command[0])).name, flush=True)
        return subprocess.run([str(x) for x in command], cwd=BASE, check=True,
                              timeout=120, env=env, capture_output=capture, text=capture)

    manifest = ET.parse(BASE / 'AndroidManifest.xml').getroot()
    if manifest.findall('uses-permission'):
        raise RuntimeError('The IO probe must remain permission-free')
    run(tools / 'aapt2', 'link', '--manifest', BASE / 'AndroidManifest.xml', '-I', android, '-o', out / 'resources.apk')
    run(java / 'javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', android,
        '-d', out / 'classes', *sorted((BASE / 'src').rglob('*.java')))
    run(tools / 'd8', '--lib', android, '--min-api', '30', '--output', out / 'dex',
        *sorted((out / 'classes').rglob('*.class')))
    unsigned = out / 'unsigned.apk'
    unsigned.write_bytes((out / 'resources.apk').read_bytes())
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(out / 'dex/classes.dex', 'classes.dex')
    run(tools / 'zipalign', '-f', '4', unsigned, out / 'aligned.apk')
    key = out / 'local-debug.keystore'
    if not key.exists():
        run(java / 'keytool', '-genkeypair', '-keystore', key, '-alias', 'localdebug',
            '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-dname', 'CN=Local File IO Probe')
    final = out / 'homeup-manual-io-probe.apk'
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'localdebug',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', final, out / 'aligned.apk')
    run(tools / 'apksigner', 'verify', '--verbose', final)
    permission_dump = run(tools / 'aapt2', 'dump', 'permissions', final, capture=True).stdout
    if 'uses-permission:' in permission_dump:
        raise RuntimeError('Unexpected permission in the built APK')
    with zipfile.ZipFile(final) as apk:
        if any(name.endswith('.exml') for name in apk.namelist()):
            raise RuntimeError('Live backup files must never be packaged')
    result = {'apk': final.name, 'sha256': hashlib.sha256(final.read_bytes()).hexdigest(),
              'size_bytes': final.stat().st_size, 'permissions': [],
              'contains_live_backups': False, 'native_launcher_restore_capability': False,
              'signing': 'locally generated debug key; not a production release key'}
    (out / 'build-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
