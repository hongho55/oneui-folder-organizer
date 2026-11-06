import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import json
import tempfile
import subprocess
import oneui_folders

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('adb_backups', ROOT/'tools/adb_backups.py')
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AdbFallbackTests(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT/'examples/synthetic.exml').read_bytes()
        self.plan = json.loads((ROOT/'examples/plan.json').read_text(encoding='utf-8'))
        self.modified, _ = oneui_folders.build(self.source, self.plan)

    def test_validate_real_conversion(self):
        report = module.validate_edit(self.source, self.modified)
        self.assertEqual(report['apps'], 6)
        self.assertTrue(report['all_other_bytes_identical'])
        self.assertFalse(report['device_restore_performed'])

    def test_accepts_inside_only_serializer_whitespace(self):
        data = self.modified.replace(b'<appOrder>\n', b'<appOrder>\n\n')
        self.assertEqual(module.validate_edit(self.source,data)['modified_sha256'],oneui_folders.sha256(data))

    def test_rejects_protected_home_change(self):
        with self.assertRaises(ValueError): module.validate_edit(self.source,self.modified.replace(b'appWidgetID="77"',b'appWidgetID="78"'))

    def test_rejects_app_metadata_change(self):
        # Existing hidden attr absence is preserved; adding it is not allowed.
        changed=self.modified.replace(b'packageName="example.fixture.gamma"',b'hidden="0" packageName="example.fixture.gamma"')
        with self.assertRaises(ValueError): module.validate_edit(self.source,changed)

    def test_rejects_wrong_pages(self):
        changed=self.modified.replace(b'<folder screen="1"',b'<folder screen="99"')
        with self.assertRaises(ValueError): module.validate_edit(self.source,changed)

    def test_rejects_duplicate_missing_component(self):
        changed=self.modified.replace(b'example.fixture.beta',b'example.fixture.alpha').replace(b'example.fixture.Beta',b'example.fixture.Alpha')
        with self.assertRaises(ValueError): module.validate_edit(self.source,changed)

    def test_exact_native_filename_validation(self):
        self.assertEqual(module.native_name('1700000000000.exml'),'1700000000000.exml')
        for value in ['../1700000000000.exml','1700000000000_front.exml','x.exml','17000000000000.exml','1700000000000.exml;id']:
            with self.subTest(value=value), self.assertRaises(ValueError):module.native_name(value)

    def test_requires_explicit_serial(self):
        for value in ['',None,'--help']:
            with self.subTest(value=value),self.assertRaises(ValueError):module.Adb(value)


class LocalShellAdb(module.Adb):
    """Run the actual copy shell protocol locally; this is not a device test."""
    def run(self, *args, check=True):
        if len(args) != 2 or args[0] != 'shell':
            raise AssertionError(args)
        result = subprocess.run(['/bin/sh', '-c', args[1]], capture_output=True, text=True)
        if check and result.returncode:
            raise RuntimeError(result.stderr or 'local copy failed')
        return result


class ExclusiveCopyTests(unittest.TestCase):
    def test_copy_and_existing_destination_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'source file', Path(directory)/'output file'
            source.write_bytes(b'payload')
            adb = LocalShellAdb('local-shell-test-only')
            adb.copy_exclusive(str(source),str(target))
            self.assertEqual(target.read_bytes(),b'payload')
            source.write_bytes(b'other')
            with self.assertRaises(ValueError):adb.copy_exclusive(str(source),str(target))
            self.assertEqual(target.read_bytes(),b'payload')

    def test_failed_copy_removes_only_own_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'missing', Path(directory)/'output'
            with self.assertRaises(RuntimeError):LocalShellAdb('local-shell-test-only').copy_exclusive(str(source),str(target))
            self.assertFalse(target.exists())

    def test_final_exclusive_open_race_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'source', Path(directory)/'existing'
            source.write_bytes(b'new')
            target.write_bytes(b'original')
            adb=LocalShellAdb('local-shell-test-only')
            with patch.object(adb,'exists',return_value=False):
                with self.assertRaises(RuntimeError):adb.copy_exclusive(str(source),str(target))
            self.assertEqual(target.read_bytes(),b'original')

    def test_dangling_symlink_is_not_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target, victim = Path(directory)/'source', Path(directory)/'link', Path(directory)/'victim'
            source.write_bytes(b'payload')
            target.symlink_to(victim)
            with self.assertRaises(RuntimeError):LocalShellAdb('local-shell-test-only').copy_exclusive(str(source),str(target))
            self.assertTrue(target.is_symlink())
            self.assertFalse(victim.exists())


if __name__=='__main__':unittest.main()
