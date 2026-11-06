"""Explicit-device ADB fallback for Home Up backups. Never restores the launcher."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import oneui_folders as editor

REMOTE = '/sdcard/Android/data/com.samsung.android.app.homestar/files/.AutoBackup'
PREVIEW = '/sdcard/Android/data/com.samsung.android.app.homestar/files/.AutoBackupPreview'


def native_name(name):
    if not isinstance(name, str) or not re.fullmatch(r'[0-9]{13}\.exml', name):
        raise ValueError('Expected the exact 13-digit native backup filename')
    return name


def canonical(node):
    return node.tag, tuple(sorted(node.attrib.items())), (node.text or '').strip(), tuple(canonical(n) for n in node)


def validate_edit(source, modified):
    """Accept equivalent native XML serializers, but protect every non-appOrder byte."""
    before, after = editor.parse_exml(source), editor.parse_exml(modified)
    editor.source_items(before)
    editor.source_items(after)
    for data in (source, modified):
        if data.count(b'<appOrder>') != 1 or data.count(b'</appOrder>') != 1:
            raise ValueError('Ambiguous appOrder byte boundary')
    prefix, rest = source.split(b'<appOrder>')
    _, suffix = rest.split(b'</appOrder>')
    new_prefix, new_rest = modified.split(b'<appOrder>')
    _, new_suffix = new_rest.split(b'</appOrder>')
    if (prefix, suffix) != (new_prefix, new_suffix):
        raise ValueError('Protected home/dock/widget/settings bytes changed')
    folders = []
    after_order = after.find('appOrder')
    if after_order is None:
        raise ValueError('Output appOrder block absent')
    for f in after_order:
        if f.tag != 'folder':
            raise ValueError('Only named folders are supported by this preview')
        folders.append({'name': f.get('title'), 'apps': [{'package': n.get('packageName'), 'activity': n.get('className')} for n in f]})
    expected, report = editor.build(source, {'folders': folders})
    if canonical(editor.parse_exml(expected).find('appOrder')) != canonical(after.find('appOrder')):
        raise ValueError('App metadata, pages or folder structure differ from validated conversion')
    report['modified_sha256'] = editor.sha256(modified)
    return report


class Adb:
    def __init__(self, serial):
        if not serial or serial.startswith('-'):
            raise ValueError('Explicit --serial is required; use adb devices -l')
        self.serial = serial

    def run(self, *args, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, args)], capture_output=True, text=True, timeout=45)
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'ADB command failed')
        return result

    def shell(self, *args, check=True):
        return self.run('shell', shlex.join([str(x) for x in args]), check=check)

    def verify(self):
        if self.run('get-state').stdout.strip() != 'device':
            raise RuntimeError('The selected ADB target is not authorized/online')
        model = self.shell('getprop', 'ro.product.model').stdout.strip()
        return {'model': model, 'serial': self.serial}

    def names(self):
        lines = self.shell('ls', '-1', REMOTE).stdout.splitlines()
        return sorted([n for n in lines if re.fullmatch(r'[0-9]{13}\.exml', n)], reverse=True)

    def exists(self, path):
        result = self.shell('test', '-e', path, check=False)
        if result.returncode not in (0, 1):
            raise RuntimeError('Failed checking remote path')
        return result.returncode == 0

    def sha(self, path):
        result = self.shell('sha256sum', path).stdout.split()
        if not result or not re.fullmatch(r'[0-9a-f]{64}', result[0]):
            raise RuntimeError('Invalid device hash response')
        return result[0]

    def pull(self, name, output):
        native_name(name)
        if output.exists():
            raise ValueError('Refusing to overwrite local original')
        expected = self.sha(REMOTE + '/' + name)
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.oneui-adb-', dir=output.parent) as work:
            temporary = Path(work) / name
            self.run('pull', REMOTE + '/' + name, temporary)
            data = temporary.read_bytes()
            if editor.sha256(data) != expected or self.sha(REMOTE + '/' + name) != expected:
                raise RuntimeError('Backup changed during pull')
            editor.parse_exml(data)
            with output.open('xb') as file:
                file.write(data)
        return {'source_name': name, 'sha256': expected, 'output': str(output), 'bytes': len(data)}

    def copy_exclusive(self, source, target):
        if self.exists(target):
            raise ValueError('Refusing to overwrite existing remote file: ' + target)
        # First claim our own fd exclusively. Only a copy that claimed the fd
        # may remove its partially written target; existing files stay untouched.
        destination, origin = shlex.quote(target), shlex.quote(source)
        command = ('set -C; exec 3> ' + destination + ' || exit 20; '
                   'if cat ' + origin + ' >&3; then exec 3>&-; '
                   'else exec 3>&-; rm -- ' + destination + '; exit 21; fi')
        self.run('shell', command)

    def stage(self, source_name, source, edited, name):
        native_name(source_name)
        native_name(name)
        if source_name == name:
            raise ValueError('Original and staged names must differ')
        original, payload = source.read_bytes(), edited.read_bytes()
        report = validate_edit(original, payload)
        if self.sha(REMOTE + '/' + source_name) != report['source_sha256']:
            raise ValueError('Device original differs; fetch a fresh backup')
        target = REMOTE + '/' + name
        if self.exists(target):
            raise ValueError('Output name already exists; choose a new name')
        temporary = '/data/local/tmp/oneui-folder-' + uuid.uuid4().hex + '.exml'
        created = []
        try:
            self.run('push', edited, temporary)
            if self.sha(temporary) != report['modified_sha256']:
                raise RuntimeError('Uploaded payload hash mismatch')
            self.copy_exclusive(temporary, target)
            created.append(target)
            if self.sha(target) != report['modified_sha256']:
                raise RuntimeError('Device staged payload read-back hash mismatch')
            old, new = source_name[:-5], name[:-5]
            companion_results = {}
            for label, a, b in [('front', REMOTE+'/'+old+'_front.exml', REMOTE+'/'+new+'_front.exml'), ('preview', PREVIEW+'/'+old+'.png', PREVIEW+'/'+new+'.png')]:
                copied = False
                if self.exists(a):
                    self.copy_exclusive(a, b)
                    created.append(b)
                    if self.sha(a) != self.sha(b):
                        raise RuntimeError('Companion copy hash mismatch')
                    copied = True
                companion_results[label+'_copied'] = copied
            return {'name': name, 'sha256': report['modified_sha256'], 'apps': report['apps'], 'folders': report['folder_count'], 'protected_bytes_identical': True, 'launcher_restored': False, **companion_results}
        except Exception:
            # Only exact paths successfully created by this invocation may be removed.
            for path in reversed(created):
                self.shell('rm', path, check=False)
            raise
        finally:
            self.shell('rm', '-f', temporary, check=False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ['list', 'pull', 'stage']:
        sub = commands.add_parser(name)
        sub.add_argument('--serial', required=True, help='Exact authorized target from adb devices -l')
        if name == 'pull':
            sub.add_argument('--name', help='Exact source filename; defaults to newest')
            sub.add_argument('--output', type=Path, required=True)
        if name == 'stage':
            sub.add_argument('--source-name', required=True)
            sub.add_argument('--source', type=Path, required=True)
            sub.add_argument('--edited', type=Path, required=True)
            sub.add_argument('--name', help='New 13-digit timestamp.exml; defaults to now')
    args = parser.parse_args(argv)
    try:
        adb = Adb(args.serial)
        identity = adb.verify()
        if args.command == 'list':
            result = {'device': identity, 'backups': adb.names()}
        elif args.command == 'pull':
            names = adb.names()
            if not names:
                raise ValueError('No Home Up backups; create one manually first')
            result = adb.pull(args.name or names[0], args.output)
        else:
            result = adb.stage(args.source_name, args.source, args.edited, args.name or str(time.time_ns()//1000000)+'.exml')
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print('Error: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
