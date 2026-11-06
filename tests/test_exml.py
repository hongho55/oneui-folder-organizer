import collections
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import oneui_folders as editor

ROOT = Path(__file__).resolve().parents[1]


class ExmlTests(unittest.TestCase):
    def setUp(self):
        self.raw = (ROOT / 'examples/synthetic.exml').read_bytes()
        self.plan = json.loads((ROOT / 'examples/plan.json').read_text(encoding='utf-8'))

    def test_valid_component_coverage_and_folder_names(self):
        payload, report = editor.build(self.raw, self.plan)
        root = editor.parse_exml(payload)
        self.assertEqual(report['apps'], 6)
        self.assertEqual(report['folder_count'], 3)
        self.assertEqual([n.get('title') for n in root.find('appOrder')], [f['name'] for f in self.plan['folders']])
        self.assertTrue(report['all_components_and_nonpage_metadata_preserved'])
        self.assertFalse(report['device_restore_performed'])

    def test_every_protected_byte_preserved(self):
        payload, report = editor.build(self.raw, self.plan)
        a, b = self.raw.split(b'<appOrder>')
        _, c = b.split(b'</appOrder>')
        x, y = payload.split(b'<appOrder>')
        _, z = y.split(b'</appOrder>')
        self.assertEqual(a, x)
        self.assertEqual(c, z)
        self.assertEqual(editor.sha256(a), report['protected_prefix_sha256'])
        self.assertEqual(editor.sha256(c), report['protected_suffix_sha256'])

    def test_nonpage_metadata_preserved(self):
        payload, _ = editor.build(self.raw, self.plan)
        before = {editor.component(n): n.attrib.copy() for n in editor.parse_exml(self.raw).find('appOrder').iter('favorite')}
        for n in editor.parse_exml(payload).find('appOrder').iter('favorite'):
            a, b = before[editor.component(n)], n.attrib.copy()
            a.pop('screen', None); b.pop('screen', None)
            self.assertEqual(a, b)

    def test_native_apps_page_capacity(self):
        payload, report = editor.build(self.raw, self.plan)
        self.assertEqual(report['app_pages'], 2)
        self.assertEqual([n.get('screen') for n in editor.parse_exml(payload).find('appOrder')], ['0', '0', '1'])

    def test_native_folder_page_capacity(self):
        plan = {'folders':[{'name':'가상 전체', 'apps':[a for f in self.plan['folders'] for a in f['apps']]}]}
        payload, _ = editor.build(self.raw, plan)
        self.assertEqual([n.get('screen') for n in editor.parse_exml(payload).find('appOrder/folder')], ['0','0','0','0','1','1'])

    def test_reject_missing_assignment(self):
        self.plan['folders'].pop()
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_duplicate_assignment(self):
        self.plan['folders'][1]['apps'][0] = copy.deepcopy(self.plan['folders'][0]['apps'][0])
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_unknown_component(self):
        self.plan['folders'][0]['apps'][0]['package'] = 'example.fixture.unknown'
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_empty_folder_name(self):
        self.plan['folders'][0]['name'] = '   '
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_duplicate_folder_name(self):
        self.plan['folders'][1]['name'] = self.plan['folders'][0]['name']
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_singleton_folder(self):
        self.plan['folders'][0]['apps'].pop()
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_reject_hidden_app(self):
        raw = self.raw.replace(b'hidden="0"', b'hidden="1"', 1)
        with self.assertRaises(ValueError): editor.build(raw, self.plan)

    def test_reject_duplicate_source_component(self):
        raw = self.raw.replace(b'example.fixture.beta', b'example.fixture.alpha').replace(b'example.fixture.Beta', b'example.fixture.Alpha')
        with self.assertRaises(ValueError): editor.build(raw, self.plan)

    def test_reject_missing_source_identifier(self):
        raw = self.raw.replace(b'packageName="example.fixture.alpha"', b'', 1)
        with self.assertRaises(ValueError): editor.build(raw, self.plan)

    def test_reject_unknown_source_item(self):
        with self.assertRaises(ValueError): editor.build(self.raw.replace(b'<appOrder>', b'<appOrder><unknown/>'), self.plan)

    def test_reject_nested_folder(self):
        raw = self.raw.replace(b'<folder screen="0"', b'<folder><folder screen="0"', 1).replace(b'</folder>', b'</folder></folder>', 1)
        with self.assertRaises(ValueError): editor.build(raw, self.plan)

    def test_reject_non_custom_order(self):
        with self.assertRaises(ValueError): editor.build(self.raw.replace(b'>CUSTOM<', b'>ALPHABETIC<'), self.plan)

    def test_reject_invalid_grid(self):
        for grid in [b'0X2', b'2x2', b'bad', b'']:
            with self.subTest(grid=grid), self.assertRaises(ValueError): editor.build(self.raw.replace(b'2X2', grid), self.plan)

    def test_reject_invalid_app_page_grid(self):
        for value in [b'0', b'-1', b'no']:
            with self.subTest(value=value), self.assertRaises(ValueError): editor.build(self.raw.replace(b'<Rows_appOrder>1', b'<Rows_appOrder>'+value), self.plan)

    def test_reject_doctype_and_entity(self):
        for declaration in [b'<!DOCTYPE exml SYSTEM "file:///not-real">', b'<!ENTITY test "value">']:
            with self.subTest(declaration=declaration), self.assertRaises(ValueError): editor.parse_exml(declaration+self.raw)

    def test_reject_multiple_or_ambiguous_blocks(self):
        for raw in [self.raw+b'<appOrder/>', self.raw+b'<!--<appOrder>-->']:
            with self.subTest(raw=raw[-40:]), self.assertRaises(ValueError): editor.build(raw, self.plan)

    def test_reject_malformed_utf8_xml_and_oversize(self):
        for raw in [b'', b'\xff', b'<appOrder>', b'X'*(editor.MAX_BYTES+1)]:
            with self.subTest(length=len(raw)), self.assertRaises(ValueError): editor.parse_exml(raw)

    def test_inspect_preserves_exact_identifiers(self):
        info = editor.inspect(self.raw)
        expected = [a for f in self.plan['folders'] for a in f['apps']]
        self.assertEqual(info['components'], expected)
        self.assertEqual(info['apps'], len(expected))

    def test_reject_non_string_or_malformed_plan(self):
        for plan in [None, [], {}, {'folders':[]}, {'folders':[None]}]:
            with self.subTest(plan=plan), self.assertRaises(ValueError): editor.build(self.raw, plan)
        self.plan['folders'][0]['apps'][0]['activity'] = 123
        with self.assertRaises(ValueError): editor.build(self.raw, self.plan)

    def test_cli_build_and_overwrite_protection(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)/'result.exml'
            args = [sys.executable, str(ROOT/'oneui_folders.py'), 'build', '--source', str(ROOT/'examples/synthetic.exml'), '--plan', str(ROOT/'examples/plan.json'), '--output', str(output)]
            result = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            original = output.read_bytes()
            self.assertEqual(editor.sha256(original), report['modified_sha256'])
            self.assertEqual(json.loads(output.with_suffix('.validation.json').read_text()), report)
            again = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(again.returncode, 2)
            self.assertEqual(output.read_bytes(), original)

    def test_cli_refuses_source_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)/'input.exml'; source.write_bytes(self.raw)
            result = subprocess.run([sys.executable, str(ROOT/'oneui_folders.py'), 'build', '--source', str(source), '--plan', str(ROOT/'examples/plan.json'), '--output', str(source)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(source.read_bytes(), self.raw)

    def test_invalid_input_does_not_write_output(self):
        with tempfile.TemporaryDirectory() as temp:
            source=Path(temp)/'input.exml'; source.write_bytes(b'invalid')
            output=Path(temp)/'output.exml'
            result=subprocess.run([sys.executable, str(ROOT/'oneui_folders.py'), 'build', '--source', str(source), '--plan', str(ROOT/'examples/plan.json'), '--output', str(output)],capture_output=True,text=True)
            self.assertEqual(result.returncode,2)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_suffix('.validation.json').exists())


if __name__ == '__main__':
    unittest.main()
