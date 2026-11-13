"""Score API observations against frozen fixtures, never generate model responses."""
from collections import Counter
from datetime import datetime
from fractions import Fraction
import math
import json

from validate_parser_dataset import keys, require, FIELDS
from validate_scenarios import integer, unique, minutes, intervals, iso

OUTCOMES = {'PASS','SEMANTIC_FAIL','CONTRACT_FAIL','MODEL_OUTPUT_INVALID','PROVIDER_ERROR',
            'TIMEOUT','API_ERROR','ENV_BLOCKED','DATASET_INVALID','SKIPPED'}
CAUSES = {'QWEN_DISABLED','QWEN_TIMEOUT','QWEN_INVALID_IDS','OSRM_UNAVAILABLE','QWEN_CAPACITY',
          'MODEL_OUTPUT_INVALID','PROVIDER_ERROR','TIMEOUT'}
REC_ITEM = {'vehicleId','vehicleName','seats','transmission','rentalDays','rentalTotal','oneWayFee','total','score','reasonCodes'}
IT_ITEM = {'sortOrder','locationId','locationName','categoryName','latitude','longitude','arrival','departure',
           'dwellMinutes','waitMinutes','travelToNextMinutes','distanceToNextMeters','hoursState','fullHoursDescription',
           'hoursEvidenceUrl','hoursVerifiedAt','durationEvidenceUrl','durationVerifiedAt','osmAttribution','contractVersion','visitMetadata'}


def same(a,b):
    return json.dumps(a,sort_keys=True,ensure_ascii=False) == json.dumps(b,sort_keys=True,ensure_ascii=False)


def text(value):
    require(isinstance(value,str) and bool(value.strip()),'string')


def strings(value):
    require(isinstance(value,list) and all(isinstance(s,str) for s in value),'string array')


def instant(value):
    text(value)
    require(datetime.fromisoformat(value.replace('Z','+00:00')).utcoffset() is not None,'timestamp timezone')


def number(value):
    require(type(value) in (int,float) and math.isfinite(value) and value>=0,'finite nonnegative number')


def close(a,b):
    number(a); number(b)
    return abs(a-b)<=0.01


def parser_score(case,body):
    keys(body,{'status','draft','unresolvedFields'},'parser response')
    require(body['status'] in ('READY_FOR_REVIEW','NEEDS_CLARIFICATION','OUT_OF_SCOPE'),'parser status')
    unresolved=unique(body['unresolvedFields'])
    require(unresolved <= {'pickupDate','returnDate','pickupBranchName','returnBranchName','passengers','budgetVndTotal','transmission','conflicts'},'unresolved fields')
    if body['draft'] is not None:
        d=body['draft']; keys(d,FIELDS,'draft')
        for k,v in d.items():
            if v is None: continue
            if k=='passengers': integer(v,2,9)
            elif k=='budgetVndTotal': integer(v)
            else: text(v)
            if k.endswith('Date'): iso(v)
        require(d['transmission'] in (None,'AUTO','MANUAL'),'transmission')
    return any(v['httpStatus']==200 and body['status']==v['status'] and same(body['draft'],v['draft'])
               and unresolved==set(v['unresolvedFields']) for v in case['expected']['variants'])


def recommendation_score(case,body,facts):
    keys(body,{'requestId','status','rankingVersion','criteria','unresolvedFields','results','factsAsOf','warnings'},'recommendation response')
    require(body['requestId']==case['input']['requestId'] and same(body['criteria'],case['input']['criteria']),'criteria echo')
    require(body['rankingVersion']=='rules-2026-10-18-v1','ranking version')
    require(body['status'] in ('OK','NO_MATCH','NEEDS_CLARIFICATION'),'recommendation status')
    require(not unique(body['unresolvedFields']),'unexpected unresolved')
    instant(body['factsAsOf']); strings(body['warnings'])
    rows=body['results']; require(isinstance(rows,list) and len(rows)<=20,'results limit')
    ids=unique([v['vehicleId'] for v in rows]); exp=case['expected']
    require(ids <= set(exp['eligibleVehicleIds']),'hard filter violation')
    catalog={v['id']:v for v in facts['vehicles']}; scenario=facts['scenarios'][case['fixtureRef']]
    previous=None
    for item in rows:
        keys(item,REC_ITEM,'recommendation item')
        vid=item['vehicleId']; v=catalog[vid]; audit=scenario['audit'][vid]
        require(item['vehicleName']==v['name'] and item['transmission']==v['transmission'],'vehicle facts')
        for k in ('seats','rentalDays','rentalTotal','oneWayFee','total','score'): integer(item[k])
        require(item['seats']==v['seats'] and all(item[k]==audit[k] for k in ('rentalDays','rentalTotal','oneWayFee','total')),'money/seat facts')
        reasons=unique(item['reasonCodes'])
        require(reasons==set(exp['requiredReasonCodes'][vid]) and not reasons & set(exp['forbiddenReasonCodes'][vid]),'reason facts')
        c=case['input']['criteria']; p=c['preferences']; requested=(p['transmission'] is not None)+len(p['amenityIds'])
        matched=(p['transmission'] is not None and p['transmission']==v['transmission'])+sum(a in v['amenityIds'] for a in p['amenityIds'])
        budget=Fraction(c['budgetVndTotal']-item['total'],c['budgetVndTotal']) if c['budgetVndTotal'] else Fraction(1)
        score=40*budget+25*Fraction(c['passengers'],v['seats'])+35*(Fraction(matched,requested) if requested else 1)
        expected_score=math.floor(score+Fraction(1,2))
        require(item['score']==expected_score,'score facts')
        rank=(-item['score'],vid)
        require(previous is None or previous<=rank,'server ordering')
        previous=rank
    require((body['status']=='OK')==bool(rows),'status/results inconsistency')
    return body['status']==exp['status'] and (case['category']!='MATCH' or bool(set(v['vehicleId'] for v in rows[:3]) & set(exp['acceptableVehicleIds'])))


def itinerary_score(case,body,facts):
    keys(body,{'status','criteria','orderedLocationIds','items','totals','fingerprint','notices'},'itinerary response')
    require(body['status'] in ('VALIDATED','NEEDS_DATA','NO_FEASIBLE_PLAN','UNAVAILABLE'),'itinerary status')
    c=body['criteria']; inp=case['input']; s=facts['scenarios'][case['fixtureRef']]
    keys(c,{'contractVersion','planVersion','tripDate','startTime','endTime','startLocationId','startLocationName','preferredCategoryId','fingerprint'},'itinerary criteria')
    require(c['contractVersion']=='kdrive-itinerary-v1' and type(c['planVersion']) is int and c['planVersion']==1,'contract version')
    require(all(same(c[k],inp[k]) for k in inp),'criteria echo')
    locs={l['id']:l for l in s['locations']}; require(c['startLocationName']==locs[inp['startLocationId']]['name'],'start name')
    strings(body['notices']); order=body['orderedLocationIds']; chosen=unique(order)
    require(chosen<=locs.keys(),'invented location')
    if body['status']!='VALIDATED':
        require(body['items']==[] and body['totals'] is None and body['fingerprint'] is None,'failed preview contains schedule')
        return body['status']==case['expected']['status']
    require(2<=len(order)<=4 and order[0]==inp['startLocationId'] and chosen<=set(s['proof']['candidateIds']),'route constraints')
    require(set(case['expected']['requiredLocationIds'])<=chosen and not chosen & set(case['expected']['forbiddenLocationIds']),'location constraints')
    text(body['fingerprint']); require(c['fingerprint'] in (None,body['fingerprint']),'fingerprint consistency')
    items=body['items']; require(isinstance(items,list) and len(items)==len(order),'schedule size')
    legs={(l['source'],l['target']):l for l in s['legs']}; arrival=minutes(inp['startTime'])
    sums=[0,0,0,0]
    for i,(lid,item) in enumerate(zip(order,items)):
        keys(item,IT_ITEM,'schedule item'); l=locs[lid]; m=l['metadata']
        integer(item['sortOrder'],1,4); integer(item['dwellMinutes'],1,1440); integer(item['waitMinutes'],0,1440)
        require(item['sortOrder']==i+1 and item['locationId']==lid and item['locationName']==l['name'],'location identity')
        # Fixture categories use their logical ID as display name.
        require(item['categoryName']==l['categoryId'] and type(item['latitude']) in (int,float) and type(item['longitude']) in (int,float)
                and item['latitude']==round(l['latitude'],7) and item['longitude']==round(l['longitude'],7),'location catalog facts')
        normalized=dict(m); normalized['weekly']={day:m['weekly'].get(day,{'state':'UNKNOWN','intervals':[]})
            for day in ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')}
        require(item['contractVersion']=='kdrive-itinerary-v1' and same(item['visitMetadata'],normalized),'metadata snapshot')
        require(item['dwellMinutes']==m['durationMinutes'] and minutes(item['arrival'])==arrival,'arrival/duration')
        departure=minutes(item['departure']); begin=arrival+item['waitMinutes']
        require(departure==begin+item['dwellMinutes'] and departure<=minutes(inp['endTime']),'time arithmetic')
        require(any(a<=begin and departure<=b for a,b in intervals(l,iso(inp['tripDate']))),'opening interval')
        day=iso(inp['tripDate']); h=m['exceptions'].get(inp['tripDate'],m['weekly'].get(day.strftime('%A').upper(),{'state':'UNKNOWN'}))
        require(item['hoursState']==h['state'],'hours state')
        for prefix,field in [('hours','hoursEvidence'),('duration','durationEvidence')]:
            require(item[prefix+'EvidenceUrl']==m[field]['url'] and item[prefix+'VerifiedAt']==m[field]['verifiedAt'],'evidence facts')
        expected_hours=', '.join(f'{a//60:02}:{a%60:02}-{b//60:02}:{b%60:02}' for a,b in intervals(l,day))
        require(item['osmAttribution']=='© OpenStreetMap contributors; OSRM driving estimate'
                and item['fullHoursDescription']==expected_hours,'displayed routing/hours facts')
        sums[0]+=item['dwellMinutes']; sums[1]+=item['waitMinutes']
        if i+1<len(order):
            leg=legs[(lid,order[i+1])]; require(leg['status']=='VERIFIED','unverified route')
            integer(item['travelToNextMinutes']); travel=(leg['durationSeconds']+59)//60
            require(item['travelToNextMinutes']==travel and close(item['distanceToNextMeters'],leg['distanceMeters']),'leg facts')
            sums[2]+=travel; sums[3]+=leg['distanceMeters']; arrival=departure+travel
        else: require(item['travelToNextMinutes'] is None and item['distanceToNextMeters'] is None,'last leg')
    totals=body['totals']; fields=['totalDwellMinutes','totalWaitMinutes','totalTravelMinutes','totalDistanceMeters','locationCount']
    keys(totals,set(fields),'totals')
    for k in fields[:3]+fields[4:]: integer(totals[k])
    require([totals[k] for k in fields[:3]]==sums[:3] and close(totals['totalDistanceMeters'],sums[3]) and totals['locationCount']==len(order),'totals facts')
    return body['status']==case['expected']['status']


def score(case,obs,facts):
    result=dict(caseId=case['caseId'],suite=case['suite'],split=case['split'],category=case['category'],attempt=1,
                outcome=None,httpStatus=obs['httpStatus'],responseStatus=None,causeCode=obs['causeCode'],
                assertionResults=[],violations=[],durationMs=obs['durationMs'],expectedRef=case['caseId'])
    def finish(outcome,code):
        result['outcome']=outcome
        result['assertionResults'].append(dict(name=code,passed=outcome=='PASS'))
        if outcome!='PASS': result['violations'].append(code)
        return result
    evidence=obs['evidence']; body=obs['body']; status=obs['httpStatus']
    if not evidence['fixtureVerified']: return finish('ENV_BLOCKED','FIXTURE_NOT_VERIFIED')
    if not evidence['noMutation']: return finish('CONTRACT_FAIL','UNEXPECTED_MUTATION')
    if obs['error'] is not None: return finish(obs['error'],'TRANSPORT_'+obs['error'])
    if isinstance(body,dict): result['responseStatus']=body.get('status') if isinstance(body.get('status'),str) else None
    if case['suite']=='recommendation' and case['category']=='MATCH':
        rows=body.get('results') if isinstance(body,dict) else None
        hit=status==200 and isinstance(rows,list) and any(isinstance(r,dict) and r.get('vehicleId') in case['expected']['acceptableVehicleIds'] for r in rows[:3])
        result['assertionResults'].append(dict(name='TOP3_HIT',passed=hit))
    expected=case['expected']; expected_http=expected['variants'][0]['httpStatus'] if case['suite']=='parser' else expected['httpStatus']
    if case['category']=='PROVIDER_ADVERSE':
        cause=facts['scenarios'][case['fixtureRef']]['provider']['cause']
        if not evidence['causeConfirmed']: return finish('ENV_BLOCKED','ADVERSE_CAUSE_NOT_PROVEN')
        if obs['causeCode']!=cause: return finish('CONTRACT_FAIL','ADVERSE_WRONG_CAUSE')
        if status!=expected_http: return finish('CONTRACT_FAIL','ADVERSE_HTTP')
        if status==429: return finish('PASS','ADVERSE_GUARD')
    elif status!=expected_http:
        if status in (401,403) or expected_http in (400,401,403): return finish('CONTRACT_FAIL','HTTP_GUARD')
        if status is not None and status>=500:
            cause=obs['causeCode']
            if cause is not None and evidence['causeConfirmed']:
                outcome='TIMEOUT' if cause in ('TIMEOUT','QWEN_TIMEOUT') else 'MODEL_OUTPUT_INVALID' if cause in ('MODEL_OUTPUT_INVALID','QWEN_INVALID_IDS') else 'PROVIDER_ERROR'
                return finish(outcome,'PROVEN_'+cause)
            return finish('API_ERROR','HTTP_SERVER_ERROR')
        return finish('CONTRACT_FAIL','HTTP_STATUS')
    elif expected_http>=400:
        return finish('PASS','HTTP_GUARD')
    if case['category']!='PROVIDER_ADVERSE' and obs['causeCode'] is not None and evidence['causeConfirmed']:
        cause=obs['causeCode']
        outcome='TIMEOUT' if cause in ('TIMEOUT','QWEN_TIMEOUT') else 'MODEL_OUTPUT_INVALID' if cause in ('MODEL_OUTPUT_INVALID','QWEN_INVALID_IDS') else 'PROVIDER_ERROR'
        return finish(outcome,'PROVEN_'+cause)
    try:
        if case['suite']=='parser': matched=parser_score(case,body)
        elif case['suite']=='recommendation': matched=recommendation_score(case,body,facts)
        else: matched=itinerary_score(case,body,facts)
    except (ValueError,TypeError,KeyError,IndexError,AttributeError,ZeroDivisionError):
        return finish('CONTRACT_FAIL','RESPONSE_SCHEMA_OR_FACTS')
    if result['responseStatus']=='UNAVAILABLE' and case['category']!='PROVIDER_ADVERSE': return finish('API_ERROR','UNCLASSIFIED_UNAVAILABLE')
    return finish('PASS' if matched else 'SEMANTIC_FAIL','EXPECTED_MATCH' if matched else 'EXPECTED_MISMATCH')


def summary(results,review_state,series):
    groups={}
    for r in results: groups.setdefault(r['suite']+'/'+r['category'],[]).append(r)
    def metric(rows):
        n=len(rows); c=sum(r['outcome'] in ('PASS','SEMANTIC_FAIL') for r in rows); f=sum(r['outcome']=='PASS' for r in rows)
        return dict(planned=n,executed=sum(r['outcome'] not in ('SKIPPED','ENV_BLOCKED','DATASET_INVALID') for r in rows),
                    completed=c,correct=f,coverage=c/n if n else None,semanticAccuracy=f/c if c else None,conservativeSuccess=f/n if n else None,
                    outcomes=dict(Counter(r['outcome'] for r in rows)))
    def quantiles(values):
        values=sorted(values)
        return dict(count=len(values),p50=values[math.ceil(.5*len(values))-1] if values else None,p95=values[math.ceil(.95*len(values))-1] if values else None)
    metrics={k:metric(rows) for k,rows in groups.items()}
    latency={o:quantiles([r['durationMs'] for r in results if r['outcome']==o and r['durationMs'] is not None]) for o in sorted(OUTCOMES)}
    gates=[]
    for suite in sorted({r['suite'] for r in results}):
        rows=[r for r in results if r['suite']==suite]
        if suite=='parser':
            parts=[([r for r in rows if r['category']=='CLEAR'],95),([r for r in rows if r['category'] in ('CLARIFY','CONFLICT')],95),([r for r in rows if r['category'] in ('OUT_OF_SCOPE','PRIVACY_INJECTION')],100)]
        elif suite=='recommendation': parts=[([r for r in rows if r['category']=='MATCH'],90),([r for r in rows if r['category']!='MATCH'],100)]
        else: parts=[(rows,100)]
        for group,threshold in parts:
            m=metric(group)
            gates.append(bool(group) and m['coverage']==1 and m['correct']*100>=threshold*m['planned'])
    passed=all(gates) and bool(gates) and not any(r['outcome']=='CONTRACT_FAIL' for r in results)
    matches=[r for r in results if r['suite']=='recommendation' and r['category']=='MATCH']
    hits=sum(any(a['name']=='TOP3_HIT' and a['passed'] for a in r.get('assertionResults',[])) for r in matches)
    return dict(totals=metric(results),groups=metrics,latencyByOutcome=latency,numericGatesPassed=passed,
                recommendationHitAt3=dict(hits=hits,planned=len(matches),rate=hits/len(matches) if matches else None),
                certified=passed and review_state=='REVIEWED' and series=='live',reviewState=review_state,
                series=series,latencyLoadState='UNKNOWN',heldoutUsed=False)
