"""Negative mutations use temporary copies; no API, DB, model or application imports."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from validate_scenarios import validate, schedule

ROOT = Path(__file__).parent / 'scenarios-v1'
SCRIPT = Path(__file__).parent / 'validate_scenarios.py'


class ScenarioTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'dataset'
        shutil.copytree(ROOT, self.root)
        self.manifest = json.loads((self.root/'manifest.json').read_text(encoding='utf-8'))
        self.facts = json.loads((self.root/'facts.json').read_text(encoding='utf-8'))
        self.rec = [json.loads(s) for s in (self.root/'recommendation.jsonl').read_text(encoding='utf-8').splitlines()]
        self.iti = [json.loads(s) for s in (self.root/'itinerary.jsonl').read_text(encoding='utf-8').splitlines()]

    def save(self):
        for name, rows in [('recommendation',self.rec),('itinerary',self.iti)]:
            (self.root/(name+'.jsonl')).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
        (self.root/'facts.json').write_text(json.dumps(self.facts),encoding='utf-8')
        for p in self.manifest['profiles'].values():
            p['factsSha256'] = hashlib.sha256((self.root/'facts.json').read_bytes()).hexdigest()
        for d in self.manifest['datasets']:
            if (self.root/d['path']).is_file():
                d['sha256'] = hashlib.sha256((self.root/d['path']).read_bytes()).hexdigest()
        (self.root/'manifest.json').write_text(json.dumps(self.manifest),encoding='utf-8')

    def rejects(self, message):
        self.save()
        with self.assertRaisesRegex(ValueError,message): validate(self.root)

    def test_all_60_draft_cases(self):
        result = validate(self.root)
        self.assertEqual(result['cases'],60)
        self.assertEqual(result['reviewState'],'DRAFT')
        self.assertEqual(result['counts']['recommendation'],{'MATCH':20,'NO_MATCH':5,'INVALID_INPUT':5})
        self.assertEqual(result['counts']['itinerary'],{'FEASIBLE':10,'INFEASIBLE':5,'NEEDS_DATA':5,'PROVIDER_ADVERSE':5,'INVALID_OR_ACCESS':5})

    def test_hash_drift(self):
        with (self.root/'facts.json').open('a') as f: f.write(' ')
        with self.assertRaisesRegex(ValueError,'hash mismatch'): validate(self.root)

    def test_path_traversal(self):
        self.manifest['datasets'][0]['path']='../stolen.jsonl'
        self.rejects('unsafe relative path')

    def test_unknown_field(self):
        self.rec[0]['expected']['extra']=1
        self.rejects('expected: fields')

    def test_unknown_nested_fact(self):
        self.facts['scenarios']['iti-01']['legs'][0]['invented']=1
        self.rejects('leg: fields')

    def test_duplicate_case(self):
        self.rec[1]['caseId']='rec-01'
        self.rejects('case ID')

    def test_missing_case(self):
        self.iti.pop()
        self.rejects('30 JSONL')

    def test_quota_change(self):
        self.rec[0]['category']='NO_MATCH'
        self.rejects('match category')

    def test_self_review(self):
        self.manifest['reviewState']='REVIEWED'
        for c in self.rec+self.iti: c['label'].update(reviewState='REVIEWED',reviewer='codex')
        self.rejects('independent reviewer')

    def test_wrong_acceptable(self):
        self.rec[0]['expected']['acceptableVehicleIds']=['maint']
        self.rejects('eligible/acceptable')

    def test_soft_preference_must_not_remove_manual(self):
        self.rec[0]['expected']['eligibleVehicleIds'].remove('m4')
        self.rejects('eligible/acceptable')

    def test_budget_boundary_one_dong(self):
        self.rec[14]['input']['criteria']['budgetVndTotal']=399999
        self.rejects('eligibility audit')

    def test_fee_direction(self):
        self.facts['routes'][3]['fee']=150000
        self.rejects('money audit')

    def test_boolean_price(self):
        self.facts['vehicles'][0]['dailyPrice']=True
        self.rejects('integer/range')

    def test_float_money(self):
        self.facts['scenarios']['rec-01']['audit']['a4']['total']=400000.0
        self.rejects('integer/range')

    def test_expired_pending_changed_to_live(self):
        self.facts['scenarios']['rec-18']['bookings'][0]['expiresAt']='2027-02-01T01:00:00Z'
        self.rejects('eligibility audit')

    def test_whole_return_day_reserved(self):
        self.facts['scenarios']['rec-25']['bookings'][0]['pickupDate']='2027-02-13'
        self.rejects('eligibility audit')

    def test_following_branch_chain(self):
        self.facts['scenarios']['rec-19']['bookings'][0]['pickupBranchId']='ha'
        self.rejects('eligibility audit')

    def test_fabricated_amenity_reason(self):
        self.rec[11]['expected']['requiredReasonCodes']['a4'].append('AMENITY_MATCH:unknown-amenity')
        self.rejects('reason facts')

    def test_invalid_input_must_really_be_invalid(self):
        self.rec[25]['input']['criteria']['passengers']=4
        self.rejects('invalid cause')

    def test_witness_wait_hand_calculation(self):
        cause,items=schedule(['a','b'],self.facts['scenarios']['iti-03'],self.iti[2]['input'])
        self.assertIsNone(cause)
        self.assertEqual(items[1]['arrival'],'11:40')
        self.assertEqual(items[1]['waitMinutes'],80)
        self.assertEqual(items[1]['departure'],'14:00')

    def test_overnight_is_not_cut_by_closed_today(self):
        cause,items=schedule(['a','b'],self.facts['scenarios']['iti-05'],self.iti[4]['input'])
        self.assertIsNone(cause)
        self.assertEqual(items[-1]['departure'],'01:40')

    def test_exception_changes_weekly_closed(self):
        self.facts['scenarios']['iti-04']['locations'][1]['metadata']['exceptions']={}
        self.rejects('candidate pool')

    def test_ceil_61_seconds(self):
        _,items=schedule(['a','b'],self.facts['scenarios']['iti-06'],self.iti[5]['input'])
        self.assertEqual(items[0]['travelMinutes'],2)
        self.assertEqual(items[1]['arrival'],'08:32')

    def test_directed_legs(self):
        scenario=self.facts['scenarios']['iti-07']; inp=self.iti[6]['input']
        self.assertEqual(schedule(['a','b','c'],scenario,inp)[1][-1]['departure'],'10:00')
        self.assertEqual(schedule(['a','c','b'],scenario,inp)[1][-1]['departure'],'09:45')

    def test_no_route_not_reverse_route(self):
        scenario=self.facts['scenarios']['iti-14']; inp=self.iti[13]['input']
        self.assertEqual(schedule(['a','b'],scenario,inp)[0],'NO_ROUTE')
        self.assertIsNone(schedule(['b','a'],scenario,inp)[0])

    def test_alternative_exists_despite_proposal_failure(self):
        scenario=self.facts['scenarios']['iti-09']; inp=self.iti[8]['input']
        self.assertEqual(schedule(scenario['proposedOrder'],scenario,inp)[0],'END_TIME')
        self.assertIsNone(schedule(['a','c'],scenario,inp)[0])

    def test_wrong_witness(self):
        self.facts['scenarios']['iti-02']['proof']['witnessSchedules'][0]['items'][1]['waitMinutes']=0
        self.rejects('witness schedule')

    def test_boolean_in_witness(self):
        self.facts['scenarios']['iti-01']['proof']['witnessSchedules'][0]['items'][0]['waitMinutes']=False
        self.rejects('witness schedule')

    def test_inactive_witness(self):
        self.iti[9]['expected']['oracle']['witnessOrders'][0]=['a','c']
        self.rejects('witness order')

    def test_false_infeasible(self):
        self.iti[10]['input']['endTime']='09:10'
        self.rejects('false infeasible')

    def test_incomplete_exhaustive_proof(self):
        self.facts['scenarios']['iti-12']['proof']['rejectedOrders'].pop()
        self.rejects('exhaustive scope incomplete')

    def test_missing_directed_edge(self):
        self.facts['scenarios']['iti-07']['legs'].pop()
        self.rejects('complete directed matrix')

    def test_unknown_not_24h(self):
        self.facts['scenarios']['iti-20']['locations'][1]['metadata']['weekly']['WEDNESDAY']['state']='OPEN_24H'
        self.rejects('candidate pool')

    def test_wrong_missing_cause(self):
        self.facts['scenarios']['iti-16']['proof']['missingFacts']=['a.hours']
        self.rejects('missing facts proof')

    def test_adverse_wrong_cause(self):
        self.facts['scenarios']['iti-24']['provider']['cause']='QWEN_TIMEOUT'
        self.rejects('adverse setup')

    def test_adverse_requires_readback(self):
        self.facts['scenarios']['iti-25']['provider']['readback']=[]
        self.rejects('provider readback')

    def test_wrong_role_status(self):
        self.iti[27]['expected']['httpStatus']=403
        self.rejects('itinerary status/type')

    def test_invalid_access_payload_valid(self):
        self.iti[26]['input']['endTime']='12:00'
        self.rejects('validation case must be invalid')

    def test_orphan_profile(self):
        self.facts['scenarios']['orphan']=self.facts['scenarios']['rec-01']
        self.rejects('orphan/missing profile')

    def test_strict_json(self):
        for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.subTest(text=text):
                from validate_parser_dataset import decode
                with self.assertRaises(ValueError): decode(text)

    def test_cli_readonly_and_exit_two(self):
        snapshot=lambda: {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.iterdir() if p.is_file()}
        before=snapshot()
        args=[sys.executable,str(SCRIPT),'--root',str(self.root)]
        result=subprocess.run(args,capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout)['cases'],60)
        self.assertEqual(before,snapshot())
        with (self.root/'facts.json').open('a') as f: f.write(' ')
        before=snapshot(); result=subprocess.run(args,capture_output=True,text=True)
        self.assertEqual(result.returncode,2)
        self.assertIn('DATASET_INVALID: hash mismatch',result.stderr)
        self.assertEqual(before,snapshot())


if __name__ == '__main__': unittest.main()
