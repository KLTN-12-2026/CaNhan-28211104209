"""Evaluate frozen dev datasets via captured API responses or a prepared loopback parser API."""
import argparse
import hashlib
import http.client
import json
import math
import os
import re
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import urlsplit

import scoring
import validate_parser_dataset as parser_data
import validate_scenarios as scenarios
from validate_parser_dataset import decode, keys, require, render, checked_json

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
ENDPOINTS={'parser':'/api/v1/ai/criteria/parse','recommendation':'/api/v1/ai/recommendations','itinerary':'/api/v1/ai/itineraries/preview'}
OBS_FIELDS={'caseId','httpStatus','body','durationMs','error','causeCode','evidence'}


def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def ollama_json(path):
    conn=http.client.HTTPConnection('127.0.0.1',11434,timeout=5)
    try:
        conn.request('GET',path); response=conn.getresponse()
        require(response.status==200,'Ollama metadata unavailable')
        data=response.read(1048577);require(len(data)<=1048576,'Ollama metadata limit')
        return decode(data.decode('utf-8'))
    finally:conn.close()


def model_identity():
    models=ollama_json('/api/tags')['models']
    chosen=[m for m in models if m.get('name')=='qwen3:8b']
    require(len(chosen)==1,'qwen3:8b must already exist; no model pull')
    value=dict(name='qwen3:8b',digest=chosen[0]['digest'],runtimeVersion=ollama_json('/api/version')['version'],mode='live')
    validate_model(value)
    return value


def validate_model(value):
    if isinstance(value,str):require(value in ('synthetic','unknown'),'model metadata');return
    keys(value,{'name','digest','runtimeVersion','mode'},'model metadata')
    require(value['name'] in ('qwen3:4b','qwen3:8b') and isinstance(value['digest'],str)
            and re.fullmatch('[a-f0-9]{64}',value['digest']) and isinstance(value['runtimeVersion'],str)
            and re.fullmatch('[0-9A-Za-z.+_-]{1,50}',value['runtimeVersion']) and value['mode']=='live','pinned model metadata')


def model_load_state(identity):
    try:
        loaded=ollama_json('/api/ps')['models']
        matches=[m for m in loaded if m.get('name',m.get('model'))==identity['name']]
        if not matches:return 'COLD'
        return 'WARM' if len(matches)==1 and matches[0].get('digest')==identity['digest'] else 'UNKNOWN'
    except (OSError,ValueError,KeyError,TypeError,http.client.HTTPException):return 'UNKNOWN'


def load_cases(root,suites):
    root=Path(root).resolve()
    require(set(suites)<=set(ENDPOINTS) and suites,'suites')
    if set(suites)=={'parser'}: parser_data.validate(root)
    else:
        require('parser' not in suites,'parser has a separate dataset root')
        scenarios.validate(root)
    manifest=decode((root/'manifest.json').read_text(encoding='utf-8'))
    cases=[]; hashes={'manifest.json':digest(root/'manifest.json')}
    for entry in manifest['datasets']:
        hashes[entry['path']]=entry['sha256']
        if entry['split']!='dev' or entry['suite'] not in suites: continue
        for line in (root/entry['path']).read_text(encoding='utf-8').splitlines():
            c=decode(line); p=manifest['profiles'][c['fixtureRef']]
            c['input']=render(c['input'],p['bindings']); c['expected']=render(c['expected'],p['bindings'])
            cases.append(c)
    facts={}
    for p in manifest['profiles'].values():
        hashes[p['factsPath']]=p['factsSha256']
        facts=decode((root/p['factsPath']).read_text(encoding='utf-8'))
    return cases,facts,manifest,hashes


def observation(value):
    keys(value,OBS_FIELDS,'observation')
    require(isinstance(value['caseId'],str),'observation ID')
    require(value['httpStatus'] is None or type(value['httpStatus']) is int and 100<=value['httpStatus']<=599,'HTTP status')
    require(value['durationMs'] is None or type(value['durationMs']) in (int,float) and math.isfinite(value['durationMs']) and value['durationMs']>=0,'duration')
    require(value['error'] in (None,'TIMEOUT','API_ERROR','ENV_BLOCKED','SKIPPED'),'transport error')
    require(value['causeCode'] is None or value['causeCode'] in scoring.CAUSES,'cause code')
    keys(value['evidence'],{'fixtureVerified','noMutation','causeConfirmed'},'evidence')
    require(all(type(v) is bool for v in value['evidence'].values()),'boolean evidence')
    require(value['error'] is not None or value['httpStatus'] is not None,'missing response')
    if value['error'] in ('ENV_BLOCKED','SKIPPED'):
        require(value['httpStatus'] is None and value['durationMs'] is None and value['body'] is None,'uncalled observation')
    return value


def uncalled(cid,error='SKIPPED'):
    return dict(caseId=cid,httpStatus=None,body=None,durationMs=None,error=error,causeCode=None,
                evidence=dict(fixtureVerified=error!='ENV_BLOCKED',noMutation=True,causeConfirmed=False))


def read_capture(path,cases,hashes):
    path=Path(path).resolve(); m=decode(path.read_text(encoding='utf-8'))
    keys(m,{'schemaVersion','captureKind','datasetHashes','sourceRevision','sourceHashes','observationsPath','observationsSha256','model','graph','clockInstant'},'capture manifest')
    require(m['schemaVersion']=='kdrive-ai-capture-v1' and m['captureKind'] in ('spring-h2-synthetic','test-double','live'),'capture kind')
    require(m['datasetHashes']==hashes,'dataset drift')
    require(m['clockInstant']=='2027-02-01T00:00:00Z','clock drift')
    require(isinstance(m['sourceHashes'],dict) and m['sourceHashes'],'source hashes required')
    for relative,h in m['sourceHashes'].items(): checked_json(REPO,relative,h)
    require(isinstance(m['sourceRevision'],str) and re.fullmatch('[a-f0-9]{40}',m['sourceRevision']),'source revision')
    validate_model(m['model']);require(m['graph'] in ('synthetic','unknown'),'unpinned graph')
    rows=[]; ids=set(); planned={c['caseId'] for c in cases}
    file=checked_json(path.parent,m['observationsPath'],m['observationsSha256'])
    for line in file.read_text(encoding='utf-8').splitlines():
        row=observation(decode(line)); cid=row['caseId']
        require(cid in planned and cid not in ids,'duplicate/unplanned observation')
        ids.add(cid); rows.append(row)
    return {r['caseId']:r for r in rows},m


def http_observation(base,case,token,timeout,verified):
    """Single POST; no redirects, proxy inheritance or retries. Never log response/token."""
    u=urlsplit(base)
    require(u.scheme=='http' and u.hostname=='127.0.0.1' and u.port is not None and not u.username
            and not u.password and not u.query and not u.fragment and u.path in ('','/'),'loopback URL required')
    require(type(timeout) in (int,float) and math.isfinite(timeout) and 0<timeout<=65,'timeout 0..65s')
    conn=http.client.HTTPConnection('127.0.0.1',u.port,timeout=timeout)
    start=time.monotonic(); status=None; body=None; error=None
    def remaining():
        value=timeout-(time.monotonic()-start)
        if value<=0: raise TimeoutError()
        return value
    try:
        headers={'Content-Type':'application/json','Authorization':'Bearer '+token}
        conn.connect(); transport=conn.sock
        transport.settimeout(remaining())
        conn.request('POST',ENDPOINTS[case['suite']],body=json.dumps(case['input'],ensure_ascii=False).encode('utf-8'),headers=headers)
        transport.settimeout(remaining())
        response=conn.getresponse(); status=response.status
        chunks=[]; size=0
        while True:
            transport.settimeout(remaining())
            chunk=response.read1(min(65536,1048577-size))
            if not chunk: break
            chunks.append(chunk); size+=len(chunk)
            if size>1048576: break
        if size<=1048576:
            try: body=decode(b''.join(chunks).decode('utf-8'))
            except (ValueError,UnicodeError): body=None
    except TimeoutError: error='TIMEOUT'
    except (OSError,http.client.HTTPException): error='API_ERROR'
    finally: conn.close()
    return dict(caseId=case['caseId'],httpStatus=status,body=body,durationMs=(time.monotonic()-start)*1000,
                error=error,causeCode=None,evidence=dict(fixtureVerified=verified,noMutation=True,causeConfirmed=False))


def source_hashes():
    paths=[HERE/n for n in ('run_evaluation.py','scoring.py','validate_parser_dataset.py','validate_scenarios.py')]
    return {p.relative_to(REPO).as_posix():digest(p) for p in paths}


def execution_plan(cases,series):
    if series=='quality': return [(c,1) for c in cases]
    require({c['suite'] for c in cases}=={'parser'},'performance requires parser dev')
    selected=[]
    for category,count in [('CLEAR',10),('CLARIFY',4),('CONFLICT',2),('OUT_OF_SCOPE',2),('PRIVACY_INJECTION',2)]:
        group=sorted((c for c in cases if c['category']==category),key=lambda c:c['caseId'])
        if category=='PRIVACY_INJECTION':
            group=[next(c for c in group if 'guard_before_inference' in c['tags']),next(c for c in group if 'prompt_injection' in c['tags'])]
        require(len(group)>=count,'performance quota')
        selected.extend(group[:count])
    return [(c,attempt) for c in selected for attempt in (1,2,3)]


def run(args):
    out=Path(args.output).resolve()
    # Fail before creating output or dispatching requests, including an existing empty directory.
    require(not out.exists(),'output already exists')
    root=Path(args.dataset).resolve(); suites=args.suites.split(',')
    cases,facts,manifest,hashes=load_cases(root,suites)
    plan=execution_plan(cases,args.series)
    require(args.allow_draft or manifest['reviewState']=='REVIEWED','DRAFT requires --allow-draft exploratory')
    require(out!=root and not out.is_relative_to(root) and not root.is_relative_to(out),'output overlaps dataset')
    series='replay-synthetic'; capture=None; observations={}
    observe=getattr(args,'ollama_observe',False)
    require(not observe or not args.capture,'load-state observation requires live HTTP')
    identity=model_identity() if observe else None
    if args.capture:
        require(args.series=='quality','capture supports one-attempt quality only')
        observations,capture=read_capture(args.capture,cases,hashes)
        series='replay-'+capture['captureKind']
    else:
        require(suites==['parser'],'HTTP mode currently accepts prepared parser catalog only; scenario capture uses backend fixture')
        require(args.preflight and args.base_url,'HTTP requires preflight and base URL')
        p=decode(Path(args.preflight).read_text(encoding='utf-8'))
        keys(p,{'datasetHashes','baseUrl','clockInstant','branchFacts','sourceHashes','fixtureVerified','readOnlyConfirmed'},'HTTP preflight')
        require(p['datasetHashes']==hashes and p['branchFacts']==facts['branches'] and p['baseUrl']==args.base_url
                and p['clockInstant']==manifest['profiles'][cases[0]['fixtureRef']]['clockInstant']
                and p['fixtureVerified'] is True and p['readOnlyConfirmed'] is True,'fixture preflight mismatch')
        require(isinstance(p['sourceHashes'],dict) and p['sourceHashes'],'backend source readback required')
        for rel,h in p['sourceHashes'].items(): checked_json(REPO,rel,h)
        token=os.environ.get('KDRIVE_EVAL_TOKEN','')
        require(token and '\r' not in token and '\n' not in token,'KDRIVE_EVAL_TOKEN missing/invalid')
        u=urlsplit(args.base_url)
        require(u.scheme=='http' and u.hostname=='127.0.0.1' and u.port is not None and u.path in ('','/')
                and not u.username and not u.password and not u.query and not u.fragment,'loopback URL required')
        series='http-exploratory'
    out.mkdir(parents=True,exist_ok=False)
    runner_hashes=source_hashes()
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
    run_manifest=dict(schemaVersion='kdrive-ai-run-v1',datasetVersion=manifest['datasetVersion'],datasetHashes=hashes,
        runnerHashes=runner_hashes,sourceRevision=revision,capturedSourceRevision=capture['sourceRevision'] if capture else None,
        capturedSourceHashes=capture['sourceHashes'] if capture else p['sourceHashes'],series=series,reviewState=manifest['reviewState'],
        captureManifestSha256=digest(args.capture) if capture else None,observationsSha256=capture['observationsSha256'] if capture else None,
        profileBindings={name:profile['bindings'] for name,profile in manifest['profiles'].items()},
        attempts=1 if args.series=='quality' else 3,seriesType=args.series,
        selectedCaseIds=list(dict.fromkeys(c['caseId'] for c,_ in plan)),split='dev',model=capture['model'] if capture else identity or 'unknown',graph=capture['graph'] if capture else 'unknown',
        settings={'timeoutSeconds':args.timeout,'retries':0},hardware='unknown',clockInstant='2027-02-01T00:00:00Z',
        command={'mode':'capture' if capture else 'http','suites':suites,'allowDraft':args.allow_draft})
    (out/'manifest.json').write_text(json.dumps(run_manifest,indent=2),encoding='utf-8')
    results=[]; signatures={}; load_states=[]
    with (out/'results.jsonl').open('x',encoding='utf-8') as stream:
        for c,attempt in plan:
            state=model_load_state(identity) if observe else 'UNKNOWN'
            obs=observations.get(c['caseId'],uncalled(c['caseId'])) if capture else http_observation(args.base_url,c,token,args.timeout,True)
            r=scoring.score(c,obs,facts); r['attempt']=attempt; results.append(r)
            load_states.append(dict(caseId=c['caseId'],attempt=attempt,state=state))
            signatures.setdefault(c['caseId'],[]).append(hashlib.sha256(json.dumps([obs['httpStatus'],obs['body'],obs['error']],sort_keys=True).encode()).hexdigest())
            stream.write(json.dumps(r,ensure_ascii=False)+'\n'); stream.flush()
    require(len(results)==len(plan) and len({(r['caseId'],r['attempt']) for r in results})==len(plan),'result coverage')
    report=scoring.summary(results,manifest['reviewState'],series)
    report['certified']=False  # This dev-only runner never certifies heldout/reviewer acceptance.
    report['seriesType']=args.series
    if observe:
        report['modelIdentityStable']=model_identity()==identity
        report['latencyLoadState']='OBSERVED_BEFORE_REQUEST'
        report['latencyByLoadState']={state:scoring.summary([r for r,l in zip(results,load_states) if l['state']==state],manifest['reviewState'],series)['latencyByOutcome'] for state in ('COLD','WARM','UNKNOWN')}
        (out/'load-state.jsonl').write_text(''.join(json.dumps(s)+'\n' for s in load_states),encoding='utf-8')
    if args.series=='performance':
        report['numericGatesPassed']=None
        report['consistency']={cid:dict(attempts=len(h),distinctResponses=len(set(h)),consistent=len(set(h))==1) for cid,h in signatures.items()}
    (out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# AI evaluation — '+series,'','Review state: '+manifest['reviewState']+'. Certification: false.',
           '','| Suite/category | N | C | F | Coverage | Accuracy | F/N |','|---|---:|---:|---:|---:|---:|---:|']
    fmt=lambda x:'N/A' if x is None else f'{100*x:.2f}%'
    for name,m in report['groups'].items(): lines.append(f"| {name} | {m['planned']} | {m['completed']} | {m['correct']} | {fmt(m['coverage'])} | {fmt(m['semanticAccuracy'])} | {fmt(m['conservativeSuccess'])} |")
    lines+=['','Latency p50/p95 by outcome in summary.json; load state '+report['latencyLoadState']+'. Timeouts remain separate.',
            'No raw prompts, response bodies, credentials or error text are copied into these reports.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    if observe and not report['modelIdentityStable']:return 2
    if any(r['outcome'] in ('ENV_BLOCKED','DATASET_INVALID','SKIPPED') for r in results): return 2
    if args.series=='performance': return 0 if all(r['outcome']=='PASS' for r in results) else 1
    return 0 if report['numericGatesPassed'] else 1


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dataset',type=Path,required=True)
    p.add_argument('--suites',required=True,help='parser OR recommendation,itinerary (dev only)')
    source=p.add_mutually_exclusive_group(required=True)
    source.add_argument('--capture',type=Path); source.add_argument('--base-url')
    p.add_argument('--preflight',type=Path); p.add_argument('--output',type=Path,required=True)
    p.add_argument('--allow-draft',action='store_true'); p.add_argument('--timeout',type=float,default=65)
    p.add_argument('--series',choices=('quality','performance'),default='quality')
    p.add_argument('--ollama-observe',action='store_true',help='pin existing qwen3:8b and observe loaded state before each HTTP request')
    args=p.parse_args()
    try:
        require(math.isfinite(args.timeout) and 0<args.timeout<=65,'timeout 0..65s')
        code=run(args)
        print(json.dumps({'exit':code,'output':str(args.output),'certified':False}))
        return code
    except (ValueError,OSError,TypeError,KeyError,AttributeError,IndexError):
        # Do not echo arbitrary external text (including tokens or malformed response bodies).
        print('DATASET_INVALID_OR_ENV_BLOCKED: preflight refused; inspect local configuration.',file=sys.stderr)
        return 2


if __name__=='__main__': sys.exit(main())
