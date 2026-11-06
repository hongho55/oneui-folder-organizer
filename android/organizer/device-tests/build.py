"""Build ONLY the opt-in device integration test APK; never installs or runs it.

Build ../build.py first. The test uses the same local debug signature. Execution
requires an already approved Shizuku app, an imported native source, and explicit
`am instrument -w -e allowStage yes ...` consent to add one backup file.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.sdk or not args.java_home:
        parser.error('Set ANDROID_HOME and JAVA_HOME (or use the corresponding arguments)')
    base = Path(__file__).resolve().parent
    main_build = base.parent/'build'
    out = main_build/'device-tests'
    for directory in [out, out/'classes', out/'dex']:
        directory.mkdir(parents=True, exist_ok=True)
    sdk = Path(args.sdk).expanduser().resolve()
    java = Path(args.java_home).expanduser().resolve()/'bin'
    tools = sdk/'build-tools/36.0.0'
    android = sdk/'platforms/android-35/android.jar'
    key = main_build/'local-debug.keystore'
    if not key.is_file() or not (main_build/'classes').is_dir():
        parser.error('Build the main organizer APK first')
    env = dict(os.environ, JAVA_HOME=str(java.parent))

    def run(*command):
        subprocess.run([str(x) for x in command], env=env, check=True, timeout=120)

    jars = sorted((main_build/'deps').glob('*.jar'))
    run(java/'javac', '--release', '8', '-encoding', 'UTF-8', '-classpath',
        os.pathsep.join(map(str, [android, main_build/'classes']+jars)),
        '-d', out/'classes', base/'EditorDeviceTest.java')
    run(tools/'d8', '--lib', android, '--classpath', main_build/'oneui-folder-organizer.apk',
        '--min-api', '30', '--output', out/'dex', *sorted((out/'classes').rglob('*.class')))
    unsigned = out/'unsigned.apk'
    run(tools/'aapt2', 'link', '--manifest', base/'AndroidManifest.xml', '-I', android, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(out/'dex/classes.dex', 'classes.dex')
    run(tools/'zipalign', '-f', '4', unsigned, out/'aligned.apk')
    final = out/'oneui-editor-device-tests.apk'
    run(tools/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'localdebug',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', final, out/'aligned.apk')
    run(tools/'apksigner', 'verify', '--verbose', final)
    print('Built (not installed or run):', final.name, hashlib.sha256(final.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
