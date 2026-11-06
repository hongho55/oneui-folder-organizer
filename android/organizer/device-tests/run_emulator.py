"""Run opt-in synthetic native UI/controller checks on an EXISTING Android emulator.

NEVER accepts real hardware. Clears only the emulator's organizer app data between
scenes and changes/restores that emulator's night mode/font scale. Build and install
the editor and separate test APK first. No Shizuku/native Home Up restore is used.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--allow-reset-synthetic', action='store_true', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cases', help='Optional comma-separated case names from the fixed native matrix')
    args = parser.parse_args()

    def adb(*command, binary=False, check=True):
        result = subprocess.run(['adb', '-s', args.serial, *map(str, command)],
                                capture_output=True, text=not binary, timeout=90)
        if check and result.returncode:
            raise RuntimeError('ADB command failed: '+repr(command)+' '+str(result.stderr))
        return result.stdout

    # All mutation is below both independent emulator gates.
    if not args.serial.startswith('emulator-'):
        parser.error('Emulator serial required; physical-device app data must never be cleared')
    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or adb('shell', 'getprop', 'ro.hardware').strip() != 'ranchu':
        parser.error('Refusing a target not verified as a ranchu Android emulator')
    if adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
        parser.error('Emulator has not completed boot')
    old_font = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
    old_night = adb('shell', 'cmd', 'uimode', 'night').strip().split(':')[-1].strip()
    if old_night not in ('yes', 'no', 'auto', 'custom'):
        raise RuntimeError('Unknown original night mode; cannot promise restoration: '+old_night)
    args.output.mkdir(parents=True, exist_ok=True)
    matrix = [('empty-light','empty','no','1.0'), ('folders-light','folders','no','1.0'),
              ('detail-light','detail','no','1.0'), ('selection-light','selection','no','1.0'),
              ('proposal-light','proposal','no','1.0'), ('folders-dark','folders','yes','1.0'),
              ('detail-large','detail','no','1.3'), ('proposal-dark-large','proposal','yes','1.3'),
              ('search-light','search','no','1.0'), ('destination-light','destination','no','1.0'),
              ('result-light','result','no','1.0'), ('selection-large','selection','no','1.3'),
              ('result-dark-large','result','yes','1.3'), ('newfolder-large','newfolder','no','1.3'),
              ('manytargets-large','manytargets','no','1.3')]
    if args.cases:
        selected=set(args.cases.split(','))
        if not selected.issubset({case[0] for case in matrix}):
            parser.error('Unknown case name in --cases')
        matrix=[case for case in matrix if case[0] in selected]
    tested_apks={}
    for package, local in [('dev.local.oneuifolders','oneui-folder-organizer.apk'),
                           ('dev.local.oneuifolders.tests','device-tests/oneui-editor-device-tests.apk')]:
        built=Path(__file__).resolve().parent.parent/'build'/local
        path=adb('shell','pm','path',package).strip()
        if not path.startswith('package:') or '\n' in path:
            raise RuntimeError('Expected one installed test APK for '+package)
        actual=hashlib.sha256(adb('exec-out','cat',path[8:],binary=True)).hexdigest()
        expected=hashlib.sha256(built.read_bytes()).hexdigest()
        if actual!=expected:
            raise RuntimeError('Installed APK differs from current local build: '+package)
        tested_apks[package]=actual
    reports = []
    try:
        for name, scene, night, font in matrix:
            adb('shell', 'cmd', 'uimode', 'night', night)
            adb('shell', 'settings', 'put', 'system', 'font_scale', font)
            if adb('shell', 'pm', 'clear', 'dev.local.oneuifolders').strip() != 'Success':
                raise RuntimeError('Emulator-only app reset failed')
            output = adb('shell', 'am', 'instrument', '-w', '-e', 'allowSynthetic', 'yes',
                         '-e', 'scene', scene, 'dev.local.oneuifolders.tests/dev.local.oneuifolders.tests.WorkbenchDeviceTest')
            (args.output/(name+'.log')).write_text(output, encoding='utf-8')
            if 'INSTRUMENTATION_CODE: -1' not in output or 'INSTRUMENTATION_RESULT: error=' in output:
                failed=adb('exec-out','run-as','dev.local.oneuifolders','cat','files/workbench-failure.png',binary=True,check=False)
                if failed.startswith(bytes.fromhex('89504e470d0a1a0a')):
                    (args.output/(name+'-failed.png')).write_bytes(failed)
                raise RuntimeError('Native regression failed: '+name+'\n'+output)
            png = adb('exec-out', 'run-as', 'dev.local.oneuifolders', 'cat', 'files/workbench-'+scene+'.png', binary=True)
            if not png.startswith(bytes.fromhex('89504e470d0a1a0a')):
                raise RuntimeError('Native capture is not PNG')
            path = args.output/(name+'.png')
            path.write_bytes(png)
            found = re.search(r'^INSTRUMENTATION_RESULT: result=(\{.*\})$', output, re.M)
            report = json.loads(found.group(1)) if found else {'scene':scene,'native_result_code':-1}
            report.update({'case':name,'night':night,'font_scale':font,'screenshot':str(path.resolve())})
            reports.append(report)
            print('PASS', name, json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        adb('shell', 'cmd', 'uimode', 'night', old_night)
        if old_font == 'null':
            adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            adb('shell', 'settings', 'put', 'system', 'font_scale', old_font)
        restored_font=adb('shell','settings','get','system','font_scale').strip()
        restored_night=adb('shell','cmd','uimode','night').strip().split(':')[-1].strip()
        if restored_font!=old_font or restored_night!=old_night:
            raise RuntimeError('Emulator font/night restoration did not read back exactly')
    final = {'synthetic_emulator_only':True, 'cases':reports, 'case_count':len(reports),
             'tested_apk_sha256':tested_apks,
             'night_and_font_restored':True, 'native_launcher_restored':False}
    (args.output/'result.json').write_text(json.dumps(final, ensure_ascii=False, indent=2), encoding='utf-8')
    print('VERIFIED', len(reports), 'native scenes; no real device touched', flush=True)


if __name__ == '__main__':
    main()
