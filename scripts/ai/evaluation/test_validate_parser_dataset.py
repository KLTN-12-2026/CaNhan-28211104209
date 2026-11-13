"""Dataset integrity tests on disposable copies; no model, DB or network."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import validate_parser_dataset as checker


class ParserDatasetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'data'
        shutil.copytree(Path(__file__).parent / 'parser-v1', self.root)

    def manifest(self, change):
        path = self.root / 'manifest.json'
        value = json.loads(path.read_text(encoding='utf-8'))
        change(value)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

    def edit(self, split, index, change):
        path = self.root / (split + '.jsonl')
        rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
        change(rows[index])
        path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')
        self.manifest(lambda m: next(e for e in m['datasets'] if e['split'] == split).update(sha256=hashlib.sha256(path.read_bytes()).hexdigest()))

    def fails(self, pattern):
        with self.assertRaisesRegex(ValueError, pattern):
            checker.validate(self.root)

    def test_real_dataset_counts_and_draft_review(self):
        report = checker.validate(self.root)
        self.assertEqual(report['cases'], 200)
        self.assertEqual(report['reviewState'], 'DRAFT')
        self.assertEqual(report['counts']['dev']['CLEAR'], 50)
        self.assertEqual(report['counts']['heldout']['CLARIFY'], 20)

    def test_hash_drift_not_silently_repaired(self):
        path = self.root / 'dev.jsonl'
        path.write_bytes(path.read_bytes() + b' ')
        self.fails('hash mismatch')

    def test_path_traversal_refused(self):
        self.manifest(lambda m: m['datasets'][0].update(path='../dev.jsonl'))
        self.fails('unsafe relative path')

    def test_remote_path_refused(self):
        self.manifest(lambda m: m['datasets'][0].update(path='https://example.invalid/data'))
        self.fails('unsafe relative path')

    def test_duplicate_json_keys_refused(self):
        with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
            checker.decode('{"caseId":"a","caseId":"b"}')

    def test_nonfinite_and_malformed_json_refused(self):
        with self.assertRaisesRegex(ValueError, 'non-finite JSON'):
            checker.decode('{"budget":NaN}')
        with self.assertRaises(json.JSONDecodeError):
            checker.decode('{bad')

    def test_unknown_case_field_refused(self):
        self.edit('dev', 0, lambda c: c.update(ownerId='invented'))
        self.fails('case: fields')

    def test_float_budget_refused(self):
        self.edit('dev', 0, lambda c: c['expected']['variants'][0]['draft'].update(budgetVndTotal=3000000.5))
        self.fails('budget integer/range')

    def test_boolean_passengers_refused(self):
        self.edit('dev', 0, lambda c: c['expected']['variants'][0]['draft'].update(passengers=True))
        self.fails('passengers integer/range')

    def test_inactive_branch_refused(self):
        self.edit('dev', 0, lambda c: c['expected']['variants'][0]['draft'].update(pickupBranchId='eval-old'))
        self.fails('branch must be active')

    def test_return_before_pickup_refused(self):
        self.edit('dev', 0, lambda c: c['expected']['variants'][0]['draft'].update(returnDate='2027-02-09'))
        self.fails('return date must follow pickup')

    def test_missing_binding_refused(self):
        self.edit('dev', 0, lambda c: c['input'].update(text='{{notDeclared}}'))
        self.fails('missing binding')

    def test_unresolved_names_not_confused_with_ids(self):
        self.edit('dev', 52, lambda c: c['expected']['variants'][0].update(unresolvedFields=['pickupBranchId']))
        self.fails('missing fields differ')

    def test_duplicate_case_id_refused(self):
        self.edit('dev', 1, lambda c: c.update(caseId='parser-dev-clear-01'))
        self.fails('case ID duplicate')

    def test_group_leakage_refused(self):
        self.edit('heldout', 0, lambda c: c.update(groupId='dev-form'))
        self.fails('cross-split group leakage')

    def test_normalized_text_duplicate_refused(self):
        first = json.loads((self.root / 'dev.jsonl').read_text(encoding='utf-8').splitlines()[0])
        self.edit('heldout', 0, lambda c: c['input'].update(text='  ' + first['input']['text'] + '\n'))
        self.fails('normalized duplicate text')

    def test_count_refused_even_with_updated_hash(self):
        self.manifest(lambda m: m['datasets'][0].update(count=99))
        self.fails('split/count')

    def test_category_quota_refused(self):
        def change(case):
            case['category'] = 'CLARIFY'
            v = case['expected']['variants'][0]
            v['draft']['budgetVndTotal'] = None
            v['status'] = 'NEEDS_CLARIFICATION'
            v['unresolvedFields'] = ['budgetVndTotal']
        self.edit('dev', 0, change)
        self.fails('quotas')

    def test_fake_reviewer_not_accepted_as_draft(self):
        self.edit('dev', 0, lambda c: c['label'].update(reviewer='codex'))
        self.fails('independent reviewer required')

    def test_no_self_reviewed_label(self):
        self.manifest(lambda m: m.update(reviewState='REVIEWED'))
        self.edit('dev', 0, lambda c: c['label'].update(reviewState='REVIEWED', reviewer='codex'))
        self.fails('independent reviewer required')

    def test_false_guard_success_refused(self):
        self.edit('dev', 90, lambda c: c['expected']['variants'][0].update(httpStatus=200, status='OUT_OF_SCOPE', unresolvedFields=[]))
        self.fails('guard status')

    def test_cli_readonly_success_and_invalid_exit(self):
        script = Path(checker.__file__)
        hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.iterdir() if p.is_file()}
        result = subprocess.run([sys.executable, str(script), '--root', str(self.root)], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['cases'], 200)
        self.assertEqual(hashes, {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.iterdir() if p.is_file()})
        self.manifest(lambda m: m['datasets'][0].update(sha256='0' * 64))
        result = subprocess.run([sys.executable, str(script), '--root', str(self.root)], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(result.returncode, 2)
        self.assertIn('DATASET_INVALID: hash mismatch', result.stderr)


if __name__ == '__main__':
    unittest.main()
