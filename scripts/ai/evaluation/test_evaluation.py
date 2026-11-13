"""Deliberate correct/incorrect response vectors plus CLI and actual loopback HTTP tests."""
from copy import deepcopy
import hashlib
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

import run_evaluation as runner
import scoring
from validate_parser_dataset import render

HERE=Path(__file__).resolve().parent
DATA=HERE/'scenarios-v1'


def response(body,status=200,cause=None,confirmed=False):
    return dict(caseId='placeholder',httpStatus=status,body=body,durationMs=10,error=None,causeCode=cause,
                evidence=dict(fixtureVerified=True,noMutation=True,causeConfirmed=confirmed))


class EvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases,cls.facts,cls.manifest,cls.hashes=runner.load_cases(DATA,['recommendation','itinerary'])
        cls.by_id={c['caseId']:c for c in cls.cases}
        cls.parsers,_,_,_=runner.load_cases(HERE/'parser-v1',['parser'])

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)

    def rec_body(self):
        c=self.by_id['rec-01'];s=self.facts['scenarios']['rec-01'];catalog={v['id']:v for v in self.facts['vehicles']}
        rows=[]
        for id,score in [('a4',80),('a5',65),('m4',47),('m7',19)]:
            v=catalog[id];a=s['audit'][id]
            rows.append(dict(vehicleId=id,vehicleName=v['name'],seats=v['seats'],transmission=v['transmission'],
                **{k:a[k] for k in ('rentalDays','rentalTotal','oneWayFee','total')},score=score,reasonCodes=c['expected']['requiredReasonCodes'][id]))
        return dict(requestId='rec-01',status='OK',rankingVersion='rules-2026-10-18-v1',criteria=c['input']['criteria'],
                    unresolvedFields=[],results=rows,factsAsOf='2027-02-01T00:00:00Z',warnings=[])

    def iti_body(self,cid='iti-01'):
        c=self.by_id[cid];s=self.facts['scenarios'][cid];w=s['proof']['witnessSchedules'][0];locs={l['id']:l for l in s['locations']}
        items=[]
        for i,x in enumerate(w['items']):
            l=locs[x['locationId']];m=deepcopy(l['metadata'])
            for d in ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'):m['weekly'].setdefault(d,dict(state='UNKNOWN',intervals=[]))
            items.append(dict(sortOrder=i+1,locationId=l['id'],locationName=l['name'],categoryName=l['categoryId'],latitude=round(l['latitude'],7),longitude=round(l['longitude'],7),
                arrival=x['arrival'],departure=x['departure'],dwellMinutes=m['durationMinutes'],waitMinutes=x['waitMinutes'],travelToNextMinutes=x['travelMinutes'],distanceToNextMeters=x['distanceMeters'],
                hoursState='INTERVALS',fullHoursDescription='08:00-18:00',hoursEvidenceUrl=m['hoursEvidence']['url'],hoursVerifiedAt=m['hoursEvidence']['verifiedAt'],
                durationEvidenceUrl=m['durationEvidence']['url'],durationVerifiedAt=m['durationEvidence']['verifiedAt'],osmAttribution='© OpenStreetMap contributors; OSRM driving estimate',contractVersion='kdrive-itinerary-v1',visitMetadata=m))
        return dict(status='VALIDATED',criteria=dict(contractVersion='kdrive-itinerary-v1',planVersion=1,**c['input'],startLocationName=locs['a']['name'],fingerprint=None),orderedLocationIds=w['order'],items=items,
                    totals=dict(totalDwellMinutes=sum(x['dwellMinutes'] for x in items),totalWaitMinutes=sum(x['waitMinutes'] for x in items),totalTravelMinutes=sum(x['travelToNextMinutes'] or 0 for x in items),
                                totalDistanceMeters=sum(x['distanceToNextMeters'] or 0 for x in items),locationCount=len(items)),fingerprint='synthetic-fingerprint',notices=[])

    def outcome(self,cid,obs):return scoring.score(self.by_id[cid],obs,self.facts)['outcome']

    def test_parser_exact_variant_and_unordered_missing(self):
        c=deepcopy(next(c for c in self.parsers if c['category']=='CLARIFY'))
        v=c['expected']['variants'][0];b={k:v[k] for k in ('status','draft','unresolvedFields')};b['unresolvedFields']=list(reversed(b['unresolvedFields']))
        self.assertEqual(scoring.score(c,response(b),{})['outcome'],'PASS')

    def test_parser_wrong_value_is_semantic_not_provider(self):
        c=deepcopy(self.parsers[0]);v=c['expected']['variants'][0];b={k:deepcopy(v[k]) for k in ('status','draft','unresolvedFields')}
        b['draft']['passengers']=5
        self.assertEqual(scoring.score(c,response(b),{})['outcome'],'SEMANTIC_FAIL')

    def test_parser_wrong_types_unknown_missing_fields(self):
        c=self.parsers[0];v=c['expected']['variants'][0]
        for invalid in (True,4.0,'4'):
            with self.subTest(invalid=invalid):
                b={k:deepcopy(v[k]) for k in ('status','draft','unresolvedFields')};b['draft']['passengers']=invalid
                self.assertEqual(scoring.score(c,response(b),{})['outcome'],'CONTRACT_FAIL')

    def test_rec_hand_calculated_score_and_facts(self):self.assertEqual(self.outcome('rec-01',response(self.rec_body())),'PASS')
    def test_rec_wrong_fee_in_fourth_result(self):
        b=self.rec_body();b['results'][3]['oneWayFee']=1
        self.assertEqual(self.outcome('rec-01',response(b)),'CONTRACT_FAIL')
        r=scoring.score(self.by_id['rec-01'],response(b),self.facts)
        s=scoring.summary([r],'DRAFT','test-double')
        self.assertEqual(s['recommendationHitAt3']['hits'],1);self.assertFalse(s['numericGatesPassed'])
    def test_rec_ineligible_even_after_top_three(self):
        b=self.rec_body();b['results'][3]['vehicleId']='maint'
        self.assertEqual(self.outcome('rec-01',response(b)),'CONTRACT_FAIL')
    def test_rec_duplicate(self):
        b=self.rec_body();b['results'].append(b['results'][0])
        self.assertEqual(self.outcome('rec-01',response(b)),'CONTRACT_FAIL')
    def test_rec_fabricated_reason(self):
        b=deepcopy(self.rec_body());b['results'][0]['reasonCodes'].append('BEST_CAR_EVER')
        self.assertEqual(self.outcome('rec-01',response(b)),'CONTRACT_FAIL')
    def test_rec_wrong_order(self):
        b=self.rec_body();b['results'].reverse()
        self.assertEqual(self.outcome('rec-01',response(b)),'CONTRACT_FAIL')
    def test_rec_valid_result_missing_top3_hit(self):
        b=self.rec_body();b['results']=b['results'][1:]
        self.assertEqual(self.outcome('rec-01',response(b)),'SEMANTIC_FAIL')
    def test_itinerary_valid(self):self.assertEqual(self.outcome('iti-01',response(self.iti_body())),'PASS')
    def test_itinerary_accepts_different_valid_order(self):
        b=self.iti_body('iti-07');b['orderedLocationIds']=['a','c','b']
        b['items'][1],b['items'][2]=b['items'][2],b['items'][1]
        b['items'][1].update(sortOrder=2,arrival='08:40',departure='09:10',travelToNextMinutes=5,distanceToNextMeters=1000)
        b['items'][2].update(sortOrder=3,arrival='09:15',departure='09:45',travelToNextMinutes=None,distanceToNextMeters=None)
        b['totals']['totalTravelMinutes']=15
        self.assertEqual(self.outcome('iti-07',response(b)),'PASS')
    def test_itinerary_wrong_arrival(self):
        b=self.iti_body();b['items'][1]['arrival']='08:39'
        self.assertEqual(self.outcome('iti-01',response(b)),'CONTRACT_FAIL')
    def test_itinerary_wrong_dwell_and_totals(self):
        for field in ('dwellMinutes','waitMinutes'):
            with self.subTest(field=field):
                b=self.iti_body();b['items'][0][field]+=1
                self.assertEqual(self.outcome('iti-01',response(b)),'CONTRACT_FAIL')
        b=self.iti_body();b['totals']['locationCount']=3
        self.assertEqual(self.outcome('iti-01',response(b)),'CONTRACT_FAIL')
    def test_itinerary_distance_tolerance(self):
        b=self.iti_body();b['items'][0]['distanceToNextMeters']+=.005
        self.assertEqual(self.outcome('iti-01',response(b)),'PASS')
        b['items'][0]['distanceToNextMeters']+=1
        self.assertEqual(self.outcome('iti-01',response(b)),'CONTRACT_FAIL')
    def test_itinerary_fake_hours(self):
        b=self.iti_body();b['items'][0]['fullHoursDescription']='Open all night'
        self.assertEqual(self.outcome('iti-01',response(b)),'CONTRACT_FAIL')
    def test_adverse_needs_actual_cause(self):
        self.assertEqual(self.outcome('iti-25',response({},429,'QWEN_CAPACITY',False)),'ENV_BLOCKED')
        self.assertEqual(self.outcome('iti-25',response({},429,'QWEN_CAPACITY',True)),'PASS')
        self.assertEqual(self.outcome('iti-25',response({},429,'QWEN_TIMEOUT',True)),'CONTRACT_FAIL')
    def test_provider_and_unclassified_500_separate(self):
        self.assertEqual(self.outcome('rec-01',response({},503)),'API_ERROR')
        self.assertEqual(self.outcome('rec-01',response({},503,'QWEN_TIMEOUT',True)),'TIMEOUT')
        self.assertEqual(self.outcome('rec-01',response({},503,'QWEN_INVALID_IDS',True)),'MODEL_OUTPUT_INVALID')
        self.assertEqual(self.outcome('rec-01',response({},503,'QWEN_DISABLED',True)),'PROVIDER_ERROR')
    def test_mutation_and_fixture_guard(self):
        obs=response(self.rec_body());obs['evidence']['noMutation']=False
        self.assertEqual(self.outcome('rec-01',obs),'CONTRACT_FAIL')
        obs['evidence']['fixtureVerified']=False
        self.assertEqual(self.outcome('rec-01',obs),'ENV_BLOCKED')
    def test_guest_401_not_403(self):
        self.assertEqual(self.outcome('iti-28',response({},401)),'PASS')
        self.assertEqual(self.outcome('iti-28',response({},403)),'CONTRACT_FAIL')
    def test_metrics_hand_calculated_and_timeout_retained(self):
        rows=[dict(suite='parser',category='CLEAR',outcome=o,durationMs=i+1) for i,o in enumerate(['PASS']*47+['SEMANTIC_FAIL']+['TIMEOUT']*2)]
        s=scoring.summary(rows,'DRAFT','test-double');m=s['groups']['parser/CLEAR']
        self.assertEqual((m['planned'],m['completed'],m['correct']),(50,48,47))
        self.assertEqual(m['coverage'],.96);self.assertAlmostEqual(m['semanticAccuracy'],47/48);self.assertEqual(m['conservativeSuccess'],.94)
        self.assertFalse(s['numericGatesPassed']);self.assertFalse(s['certified']);self.assertEqual(s['latencyByOutcome']['PASS']['p95'],45)
        self.assertEqual(s['latencyByOutcome']['TIMEOUT']['count'],2)
    def test_zero_denominator_and_skips(self):
        rows=[dict(suite='parser',category='CLEAR',outcome='SKIPPED',durationMs=None)]
        s=scoring.summary(rows,'REVIEWED','live')
        self.assertIsNone(s['totals']['semanticAccuracy']);self.assertEqual(s['totals']['coverage'],0);self.assertFalse(s['certified'])
        self.assertIsNone(scoring.summary([],'DRAFT','test-double')['totals']['coverage'])
    def test_hit_at_three_threshold(self):
        rows=[dict(suite='recommendation',category='MATCH',outcome='PASS' if i<18 else 'SEMANTIC_FAIL',durationMs=1) for i in range(20)]
        rows += [dict(suite='recommendation',category='NO_MATCH',outcome='PASS',durationMs=1)]*5
        rows += [dict(suite='recommendation',category='INVALID_INPUT',outcome='PASS',durationMs=1)]*5
        self.assertTrue(scoring.summary(rows,'DRAFT','test-double')['numericGatesPassed'])
        rows[17]['outcome']='SEMANTIC_FAIL';self.assertFalse(scoring.summary(rows,'DRAFT','test-double')['numericGatesPassed'])

    def capture(self,rows,hashes=None):
        obs=self.root/'observations.jsonl';obs.write_text(''.join(json.dumps(r)+'\n' for r in rows),encoding='utf-8')
        source=HERE/'run_evaluation.py'
        m=dict(schemaVersion='kdrive-ai-capture-v1',captureKind='test-double',datasetHashes=hashes or self.hashes,sourceRevision='a'*40,
               sourceHashes={source.relative_to(runner.REPO).as_posix():runner.digest(source)},observationsPath=obs.name,observationsSha256=runner.digest(obs),model='synthetic',graph='synthetic',clockInstant='2027-02-01T00:00:00Z')
        file=self.root/'capture.json';file.write_text(json.dumps(m),encoding='utf-8');return file
    def cli(self,capture,out,extra=()):
        return subprocess.run([sys.executable,str(HERE/'run_evaluation.py'),'--dataset',str(DATA),'--suites','recommendation,itinerary','--capture',str(capture),'--output',str(out),*extra],capture_output=True,text=True,encoding='utf-8')
    def test_cli_missing_capture_keeps_all_sixty_cases(self):
        c=self.capture([]);out=self.root/'result';r=self.cli(c,out,['--allow-draft'])
        self.assertEqual(r.returncode,2,r.stderr)
        rows=[json.loads(l) for l in (out/'results.jsonl').read_text().splitlines()]
        self.assertEqual(len(rows),60);self.assertEqual(len({r['caseId'] for r in rows}),60)
        self.assertTrue(all(r['outcome']=='SKIPPED' for r in rows))
    def test_cli_refuses_existing_empty_and_populated_output(self):
        c=self.capture([]);out=self.root/'result';out.mkdir()
        for sentinel in (False,True):
            if sentinel:(out/'sentinel').write_text('keep')
            before={p.name:p.read_bytes() for p in out.iterdir()}
            r=self.cli(c,out,['--allow-draft']);self.assertEqual(r.returncode,2)
            self.assertEqual(before,{p.name:p.read_bytes() for p in out.iterdir()})
    def test_cli_requires_draft_opt_in(self):
        c=self.capture([]);out=self.root/'result';r=self.cli(c,out)
        self.assertEqual(r.returncode,2);self.assertFalse(out.exists())
    def test_duplicate_capture_refused_before_output(self):
        obs=response(self.rec_body());obs['caseId']='rec-01';c=self.capture([obs,obs]);out=self.root/'result'
        self.assertEqual(self.cli(c,out,['--allow-draft']).returncode,2);self.assertFalse(out.exists())
    def test_hash_and_dataset_drift_refused(self):
        c=self.capture([]);m=json.loads(c.read_text());m['datasetHashes']['facts.json']='0'*64;c.write_text(json.dumps(m))
        out=self.root/'result';self.assertEqual(self.cli(c,out,['--allow-draft']).returncode,2);self.assertFalse(out.exists())
    def test_reports_do_not_copy_body_or_secrets(self):
        obs=response({'secret':'DO_NOT_COPY_TOKEN'},503);obs['caseId']='rec-01';c=self.capture([obs]);out=self.root/'result'
        self.assertEqual(self.cli(c,out,['--allow-draft']).returncode,2)
        self.assertNotIn('DO_NOT_COPY_TOKEN',''.join(p.read_text() for p in out.iterdir()))
    def test_nonfinite_and_boolean_observation(self):
        for value in (float('nan'),float('inf'),True,-1):
            with self.subTest(value=value):
                obs=response({});obs['durationMs']=value
                with self.assertRaises(ValueError):runner.observation(obs)

    def test_cli_positive_and_deliberate_failure_exit_codes(self):
        cases,_,_,hashes=runner.load_cases(HERE/'parser-v1',['parser'])
        rows=[]
        for c in cases:
            v=c['expected']['variants'][0]
            b={k:deepcopy(v[k]) for k in ('status','draft','unresolvedFields')} if v['httpStatus']==200 else {'message':'synthetic guard'}
            obs=response(b,v['httpStatus']);obs['caseId']=c['caseId'];rows.append(obs)
        c=self.capture(rows,hashes)
        args=[sys.executable,str(HERE/'run_evaluation.py'),'--dataset',str(HERE/'parser-v1'),'--suites','parser','--capture',str(c),'--allow-draft','--output']
        good=self.root/'good';r=subprocess.run(args+[str(good)],capture_output=True,text=True)
        self.assertEqual(r.returncode,0,r.stderr)
        report=json.loads((good/'summary.json').read_text());self.assertEqual(report['totals']['correct'],100);self.assertFalse(report['certified'])
        for obs in rows[:3]:obs['body']['draft']['passengers']=9 if obs['body']['draft']['passengers']!=9 else 8
        self.capture(rows,hashes)
        bad=self.root/'bad';r=subprocess.run(args+[str(bad)],capture_output=True,text=True)
        self.assertEqual(r.returncode,1,r.stderr)
        self.assertEqual(json.loads((bad/'summary.json').read_text())['totals']['outcomes']['SEMANTIC_FAIL'],3)

    def test_performance_is_twenty_dev_cases_three_attempts(self):
        plan=runner.execution_plan(self.parsers,'performance')
        from collections import Counter
        self.assertEqual(len(plan),60);self.assertEqual(len({c['caseId'] for c,_ in plan}),20)
        self.assertTrue(all(c['split']=='dev' for c,_ in plan))
        self.assertEqual(Counter(c['category'] for c,a in plan if a==1),{'CLEAR':10,'CLARIFY':4,'CONFLICT':2,'OUT_OF_SCOPE':2,'PRIVACY_INJECTION':2})
        self.assertTrue(all([a for x,a in plan if x['caseId']==c['caseId']]==[1,2,3] for c,_ in plan))
        with self.assertRaises(ValueError):runner.execution_plan(self.cases,'performance')

    def test_cli_preflight_refusal_preserves_environment(self):
        before=dict(os.environ)
        out=self.root/'missing-preflight'
        r=subprocess.run([sys.executable,str(HERE/'run_evaluation.py'),'--dataset',str(HERE/'parser-v1'),'--suites','parser','--base-url','http://127.0.0.1:1','--output',str(out),'--allow-draft'],capture_output=True,text=True)
        self.assertEqual(r.returncode,2);self.assertFalse(out.exists());self.assertEqual(before,dict(os.environ))

    def server(self,status=200,body=b'{"message":"test"}',delay=0):
        calls=[]
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                calls.append((self.path,self.headers.get('Authorization')))
                self.rfile.read(int(self.headers['Content-Length']))
                time.sleep(delay)
                try:
                    self.send_response(status)
                    if status==302:self.send_header('Location','http://127.0.0.1:1/forbidden')
                    self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError):pass
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
        thread=threading.Thread(target=server.serve_forever);thread.start()
        def cleanup():server.shutdown();server.server_close();thread.join(2);self.assertFalse(thread.is_alive())
        self.addCleanup(cleanup)
        return f'http://127.0.0.1:{server.server_port}',calls
    def test_actual_http_path_auth_no_redirect_no_retry(self):
        url,calls=self.server(302)
        obs=runner.http_observation(url,self.parsers[0],'synthetic-token',1,True)
        self.assertEqual(obs['httpStatus'],302);self.assertEqual(calls,[('/api/v1/ai/criteria/parse','Bearer synthetic-token')])
        self.assertNotIn('synthetic-token',json.dumps(obs))
    def test_actual_http_timeout_no_retry(self):
        url,calls=self.server(delay=.2)
        obs=runner.http_observation(url,self.parsers[0],'synthetic-token',.05,True)
        self.assertEqual(obs['error'],'TIMEOUT');self.assertEqual(len(calls),1)
    def test_remote_and_userinfo_refused(self):
        for url in ('https://example.invalid','http://localhost:80','http://u:p@127.0.0.1:80','http://127.0.0.1:80/a'):
            with self.subTest(url=url),self.assertRaises(ValueError):runner.http_observation(url,self.parsers[0],'x',1,True)

    def test_http_cli_quality_and_performance_use_only_dev(self):
        c=self.parsers[0];v=c['expected']['variants'][0]
        body={k:v[k] for k in ('status','draft','unresolvedFields')}
        url,calls=self.server(body=json.dumps(body).encode())
        _,facts,manifest,hashes=runner.load_cases(HERE/'parser-v1',['parser'])
        preflight=dict(datasetHashes=hashes,baseUrl=url,clockInstant='2027-02-01T00:00:00Z',branchFacts=facts['branches'],
                       sourceHashes=runner.source_hashes(),fixtureVerified=True,readOnlyConfirmed=True)
        file=self.root/'preflight.json';file.write_text(json.dumps(preflight),encoding='utf-8')
        env=dict(os.environ);env['KDRIVE_EVAL_TOKEN']='private-test-sentinel'
        for series,n in [('quality',100),('performance',60)]:
            out=self.root/series;before=len(calls)
            args=[sys.executable,str(HERE/'run_evaluation.py'),'--dataset',str(HERE/'parser-v1'),'--suites','parser','--base-url',url,
                  '--preflight',str(file),'--output',str(out),'--allow-draft','--series',series,'--timeout','2']
            result=subprocess.run(args,env=env,capture_output=True,text=True)
            self.assertEqual(result.returncode,1,result.stderr)
            self.assertEqual(len(calls)-before,n)
            report=json.loads((out/'summary.json').read_text())
            self.assertEqual(report['totals']['planned'],n);self.assertFalse(report['certified'])
            self.assertNotIn('private-test-sentinel',''.join(p.read_text() for p in out.iterdir()))
            if series=='performance':
                self.assertEqual(len(report['consistency']),20);self.assertIsNone(report['numericGatesPassed'])

    def test_http_body_limit(self):
        url,calls=self.server(body=b'x'*1048577)
        obs=runner.http_observation(url,self.parsers[0],'x',2,True)
        self.assertEqual(obs['httpStatus'],200);self.assertIsNone(obs['body']);self.assertEqual(len(calls),1)


if __name__=='__main__':unittest.main()
