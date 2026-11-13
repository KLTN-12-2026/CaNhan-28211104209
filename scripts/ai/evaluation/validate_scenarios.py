"""Offline integrity and bounded synthetic oracle checks; not an API/model scorer."""
import argparse
from collections import Counter
from datetime import date, datetime, timedelta
from itertools import permutations
import json
from pathlib import Path
import re

from validate_parser_dataset import COMMON, ID, VERSION, checked_json, decode, keys, require

QUOTAS = {
    'recommendation': {'MATCH': 20, 'NO_MATCH': 5, 'INVALID_INPUT': 5},
    'itinerary': {'FEASIBLE': 10, 'INFEASIBLE': 5, 'NEEDS_DATA': 5,
                  'PROVIDER_ADVERSE': 5, 'INVALID_OR_ACCESS': 5},
}
REC_KEYS = {'kind', 'httpStatus', 'status', 'acceptableVehicleIds', 'eligibleVehicleIds',
            'requiredReasonCodes', 'forbiddenReasonCodes'}
IT_KEYS = {'kind', 'httpStatus', 'status', 'oracle', 'requiredLocationIds', 'forbiddenLocationIds'}
CRITERIA = {'pickupDate', 'returnDate', 'pickupBranchId', 'returnBranchId', 'passengers',
            'budgetVndTotal', 'preferences'}
IT_INPUT = {'tripDate', 'startTime', 'endTime', 'startLocationId', 'preferredCategoryId'}


def integer(value, minimum=0, maximum=9007199254740991):
    require(type(value) is int and minimum <= value <= maximum, 'integer/range')


def unique(values):
    require(isinstance(values, list) and all(isinstance(v, str) and v for v in values), 'string list')
    require(len(values) == len(set(values)), 'duplicate list entry')
    return set(values)


def iso(value):
    require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}', value), 'ISO date')
    return date.fromisoformat(value)


def minutes(value):
    require(isinstance(value, str) and re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', value), 'HH:mm')
    return int(value[:2]) * 60 + int(value[3:])


def rec_invalid(inp):
    keys(inp, {'requestId', 'criteria'}, 'recommendation input')
    require(isinstance(inp['requestId'], str) and ID.fullmatch(inp['requestId']), 'request ID')
    c = inp['criteria']
    if set(c) != CRITERIA:
        require(set(c) == CRITERIA | {'inventedField'}, 'unsupported invalid field fixture')
        return 'UNKNOWN_FIELD'
    p = c['preferences']
    keys(p, {'transmission', 'amenityIds'}, 'preferences')
    require(p['transmission'] in (None, 'AUTO', 'MANUAL'), 'transmission')
    integer(c['passengers'], -10, 20)
    integer(c['budgetVndTotal'], -1)
    if not 2 <= c['passengers'] <= 9:
        return 'PASSENGERS_RANGE'
    if c['budgetVndTotal'] < 0:
        return 'BUDGET_NEGATIVE'
    if iso(c['returnDate']) <= iso(c['pickupDate']):
        return 'DATE_ORDER'
    require(isinstance(p['amenityIds'], list) and all(isinstance(x, str) and x for x in p['amenityIds']), 'amenities')
    if len(p['amenityIds']) != len(set(p['amenityIds'])):
        return 'DUPLICATE_AMENITY'
    require(len(p['amenityIds']) <= 20 and all(len(x) <= 36 for x in p['amenityIds']), 'amenities range')
    return None


def recommendation(case, scenario, facts, clock):
    keys(scenario, {'suite', 'vehicleIds', 'bookings', 'audit', 'invalidCause'}, 'recommendation facts')
    expected = case['expected']
    keys(expected, REC_KEYS, 'recommendation expected')
    integer(expected['httpStatus'], 100, 599)
    require(expected['kind'] == 'RECOMMENDATION' and case['actor'] == 'USER', 'recommendation kind/actor')
    acceptable = unique(expected['acceptableVehicleIds'])
    eligible = unique(expected['eligibleVehicleIds'])
    vehicle_ids = unique(scenario['vehicleIds'])
    catalog = {v['id']: v for v in facts['vehicles']}
    require(vehicle_ids <= catalog.keys(), 'vehicle reference')
    invalid = rec_invalid(case['input'])
    require(invalid == scenario['invalidCause'], 'invalid cause mismatch')
    if case['category'] == 'INVALID_INPUT':
        require(invalid is not None and expected['httpStatus'] == 400 and expected['status'] is None,
                'invalid recommendation expectation')
        require(not acceptable and not eligible and not expected['requiredReasonCodes']
                and not expected['forbiddenReasonCodes'] and not scenario['audit'], 'invalid guard has results')
        return
    require(invalid is None and expected['httpStatus'] == 200, 'valid recommendation input')
    c = case['input']['criteria']
    start, end = iso(c['pickupDate']), iso(c['returnDate'])
    days = (end-start).days
    require(start > clock.date() and 1 <= days <= 90, 'rental dates')
    active = {b['id'] for b in facts['branches'] if b['status'] == 'ACTIVE'}
    require(c['pickupBranchId'] in active and c['returnBranchId'] in active, 'active branches')
    fees = [r['fee'] for r in facts['routes'] if (r['pickup'], r['returned']) == (c['pickupBranchId'], c['returnBranchId'])]
    require(len(fees) == 1, 'route fee missing or ambiguous')
    fee = fees[0]
    schedules = []
    for b in scenario['bookings']:
        keys(b, {'vehicleId', 'status', 'pickupDate', 'returnDate', 'pickupBranchId', 'returnBranchId', 'expiresAt'}, 'booking')
        require(b['vehicleId'] in vehicle_ids and b['status'] in ('CONFIRMED', 'PENDING'), 'supported booking fixture')
        require(iso(b['pickupDate']) <= iso(b['returnDate']) and b['pickupBranchId'] in active and b['returnBranchId'] in active, 'booking dates/branches')
        if b['status'] == 'PENDING':
            expiry = datetime.fromisoformat(b['expiresAt'].replace('Z', '+00:00'))
            require(expiry.utcoffset() == timedelta(0), 'booking expiry UTC')
            if expiry <= clock:
                continue
        else:
            require(b['expiresAt'] is None, 'confirmed expiry')
        schedules.append(b)
    require(set(scenario['audit']) == vehicle_ids, 'audit must cover every vehicle')
    computed = set()
    for vid in vehicle_ids:
        v = catalog[vid]
        exclusions = set()
        if v['status'] != 'ACTIVE': exclusions.add('INACTIVE')
        if v['seats'] < c['passengers']: exclusions.add('SEATS')
        total = days*v['dailyPrice'] + fee
        if total > c['budgetVndTotal']: exclusions.add('BUDGET')
        bookings = [b for b in schedules if b['vehicleId'] == vid]
        # Calendar fixture reserves both endpoint days, including the entire return day.
        if any(start <= iso(b['returnDate']) and iso(b['pickupDate']) <= end for b in bookings):
            exclusions.add('OVERLAP')
        requested = dict(pickupDate=c['pickupDate'], returnDate=c['returnDate'],
                         pickupBranchId=c['pickupBranchId'], returnBranchId=c['returnBranchId'])
        position = v['currentBranchId']
        for b in sorted(bookings + [requested], key=lambda b: b['pickupDate']):
            if position != b['pickupBranchId']: exclusions.add('BRANCH_CHAIN')
            position = b['returnBranchId']
        audit = scenario['audit'][vid]
        keys(audit, {'rentalDays', 'rentalTotal', 'oneWayFee', 'total', 'excludedBy'}, 'vehicle audit')
        for k in ('rentalDays', 'rentalTotal', 'oneWayFee', 'total'): integer(audit[k])
        require([audit[k] for k in ('rentalDays', 'rentalTotal', 'oneWayFee', 'total')] ==
                [days, days*v['dailyPrice'], fee, total], 'money audit mismatch')
        require(unique(audit['excludedBy']) == exclusions, 'eligibility audit mismatch')
        if not exclusions: computed.add(vid)
    require(eligible == computed and acceptable <= eligible, 'eligible/acceptable mismatch')
    require((case['category'] == 'MATCH') == bool(acceptable) == bool(eligible), 'match category')
    require(expected['status'] == ('OK' if eligible else 'NO_MATCH'), 'recommendation status')
    for name in ('requiredReasonCodes', 'forbiddenReasonCodes'):
        require(isinstance(expected[name], dict) and set(expected[name]) == eligible, 'reason map coverage')
    for vid in eligible:
        v = catalog[vid]; p = c['preferences']
        yes = {'WITHIN_TOTAL_BUDGET', 'ENOUGH_SEATS'}; no = set()
        (yes if p['transmission'] and p['transmission'] == v['transmission'] else no).add('TRANSMISSION_MATCH')
        for am in p['amenityIds']:
            (yes if am in v['amenityIds'] else no).add('AMENITY_MATCH:'+am)
        require(unique(expected['requiredReasonCodes'][vid]) == yes and
                unique(expected['forbiddenReasonCodes'][vid]) == no, 'reason facts mismatch')


def intervals(location, trip_date):
    """Calendar expansion in absolute minutes relative to trip midnight; not Java scheduling code."""
    meta = location['metadata']
    if meta is None: return []
    result = []
    for delta in (-1, 0):
        day = trip_date + timedelta(days=delta)
        h = meta['exceptions'].get(day.isoformat(), meta['weekly'].get(day.strftime('%A').upper(), {'state': 'UNKNOWN', 'intervals': []}))
        if h['state'] == 'OPEN_24H' and delta == 0: result.append((0, 1440))
        for iv in h['intervals']:
            a = minutes(iv['start']) + delta*1440
            b = minutes(iv['end']) + delta*1440 + (1440 if iv['nextDay'] else 0)
            if b > 0: result.append((max(a, 0), b))
    return sorted(result)


def schedule(order, scenario, inp):
    """Earliest feasible visit starts for a specified order on finite synthetic facts."""
    locs = {l['id']: l for l in scenario['locations']}
    legs = {(l['source'], l['target']): l for l in scenario['legs']}
    current = minutes(inp['startTime']); finish = minutes(inp['endTime']); items = []
    for i, lid in enumerate(order):
        location = locs[lid]; duration = location['metadata']['durationMinutes']
        possible = [max(current, a) for a, b in intervals(location, iso(inp['tripDate']))
                    if max(current, a) + duration <= b]
        if not possible: return 'OPENING_WINDOW', []
        begin = min(possible); departure = begin+duration
        if departure >= 1440: return 'DAY_BOUNDARY', []
        if departure > finish: return 'END_TIME', []
        travel = distance = None
        if i+1 < len(order):
            leg = legs[(lid, order[i+1])]
            if leg['status'] == 'NO_ROUTE': return 'NO_ROUTE', []
            travel = (leg['durationSeconds'] + 59)//60
            distance = leg['distanceMeters']
        fmt = lambda t: f'{t//60:02}:{t%60:02}'
        items.append(dict(locationId=lid, arrival=fmt(current), departure=fmt(departure),
                          waitMinutes=begin-current, travelMinutes=travel, distanceMeters=distance))
        current = departure + (travel or 0)
        if current >= 1440: return 'DAY_BOUNDARY', []
    return None, items


def location_facts(scenario):
    locations = scenario['locations']
    require(isinstance(locations, list) and locations, 'locations required')
    ids = unique([l['id'] for l in locations])
    for l in locations:
        keys(l, {'id', 'name', 'status', 'categoryId', 'latitude', 'longitude', 'metadata'}, 'location')
        require(l['status'] in ('ACTIVE', 'INACTIVE') and l['name'] and l['categoryId'], 'location facts')
        require(type(l['latitude']) in (int, float) and -90 <= l['latitude'] <= 90 and
                type(l['longitude']) in (int, float) and -180 <= l['longitude'] <= 180, 'coordinates')
        m = l['metadata']
        if m is None: continue
        keys(m, {'timezone', 'durationMinutes', 'weekly', 'exceptions', 'hoursEvidence', 'durationEvidence'}, 'metadata')
        require(m['timezone'] == 'Asia/Ho_Chi_Minh', 'metadata timezone')
        if m['durationMinutes'] is not None: integer(m['durationMinutes'], 1, 1440)
        require(set(m['weekly']) <= {'MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'}, 'weekday')
        for day in m['exceptions']: iso(day)
        for h in [*m['weekly'].values(), *m['exceptions'].values()]:
            keys(h, {'state', 'intervals'}, 'hours')
            require(h['state'] in ('UNKNOWN','CLOSED','OPEN_24H','INTERVALS') and isinstance(h['intervals'], list), 'hours state')
            require((h['state'] == 'INTERVALS' and 1 <= len(h['intervals']) <= 4) or
                    (h['state'] != 'INTERVALS' and not h['intervals']), 'hours intervals')
            last_end = -1
            for iv in h['intervals']:
                keys(iv, {'start','end','nextDay'}, 'interval')
                require(type(iv['nextDay']) is bool, 'nextDay boolean')
                a,b = minutes(iv['start']), minutes(iv['end']) + (1440 if iv['nextDay'] else 0)
                require(last_end <= a < b <= a+1440, 'interval order/duration')
                last_end = b
        for field in ('hoursEvidence','durationEvidence'):
            ev = m[field]
            if field == 'durationEvidence' and m['durationMinutes'] is None:
                require(ev is None, 'null duration evidence'); continue
            keys(ev, {'url','verifiedAt'}, 'synthetic evidence')
            require(ev['url'].startswith('https://example.invalid/'), 'synthetic evidence URL')
            verified = datetime.fromisoformat(ev['verifiedAt'].replace('Z','+00:00'))
            require(verified.utcoffset() == timedelta(0) and '2000-01-01' <= ev['verifiedAt'] <= '2027-02-01T00:00:00Z', 'evidence date')
    edges = set()
    for leg in scenario['legs']:
        keys(leg, {'source','target','status','durationSeconds','distanceMeters'}, 'leg')
        pair = (leg['source'], leg['target'])
        require(pair[0] in ids and pair[1] in ids and pair[0] != pair[1] and pair not in edges, 'directed leg ID')
        edges.add(pair)
        require(leg['status'] in ('VERIFIED','NO_ROUTE'), 'route fixture status')
        if leg['status'] == 'VERIFIED':
            integer(leg['durationSeconds'], 1); integer(leg['distanceMeters'], 1)
        else: require(leg['durationSeconds'] is None and leg['distanceMeters'] is None, 'no-route invented facts')
    require(edges == set(permutations(ids, 2)), 'complete directed matrix required')


def itinerary(case, s, clock):
    keys(s, {'suite','locations','legs','provider','proposedOrder','proof'}, 'itinerary facts')
    location_facts(s)
    inp = case['input']; exp = case['expected']; proof = s['proof']; oracle = exp['oracle']
    keys(inp, IT_INPUT, 'itinerary input'); keys(exp, IT_KEYS, 'itinerary expected')
    keys(oracle, {'type','witnessOrders','evidenceRef'}, 'oracle')
    keys(proof, {'candidateIds','minPoints','maxPoints','startFixed','witnessSchedules','rejectedOrders','missingFacts','scope'}, 'proof')
    require(exp['kind'] == 'ITINERARY' and oracle['evidenceRef'] == 'facts.json#'+case['fixtureRef'], 'oracle reference')
    required, forbidden = unique(exp['requiredLocationIds']), unique(exp['forbiddenLocationIds'])
    loc_ids = {l['id'] for l in s['locations']}
    require(not required & forbidden and required | forbidden <= loc_ids, 'required/forbidden locations')
    require(proof['minPoints'] == 2 and proof['maxPoints'] == 4 and proof['startFixed'] == inp['startLocationId'] and proof['scope'], 'oracle scope')
    integer(proof['minPoints']); integer(proof['maxPoints'])
    candidates = unique(proof['candidateIds'])
    trip = iso(inp['tripDate'])
    require(clock.date() < trip <= clock.date()+timedelta(days=90), 'trip range')
    computed = {l['id'] for l in s['locations'] if l['status'] == 'ACTIVE' and l['metadata'] is not None
                and l['metadata']['durationMinutes'] is not None and intervals(l, trip)}
    require(candidates == computed, 'candidate pool mismatch')
    keys(s['provider'], {'qwen','osrm','cause','readback'}, 'provider')
    unique(s['provider']['readback']); require(s['provider']['readback'], 'provider readback missing')
    category = case['category']
    statuses = {'FEASIBLE':('VALIDATED','KNOWN_FEASIBLE'), 'INFEASIBLE':('NO_FEASIBLE_PLAN','EXHAUSTIVE_INFEASIBLE'),
                'NEEDS_DATA':('NEEDS_DATA','MISSING_FACTS'), 'PROVIDER_ADVERSE':('UNAVAILABLE','EXPECTED_PROVIDER_FAILURE'),
                'INVALID_OR_ACCESS':(None,'INVALID_OR_ACCESS')}
    status, kind = statuses[category]
    http = 200
    if category == 'PROVIDER_ADVERSE':
        cause = s['provider']['cause']
        require(cause in {'QWEN_DISABLED','QWEN_TIMEOUT','QWEN_INVALID_IDS','OSRM_UNAVAILABLE','QWEN_CAPACITY'}, 'adverse cause')
        require(s['provider']['qwen'] == ('VALID_SEQUENCE' if cause == 'OSRM_UNAVAILABLE' else cause)
                and s['provider']['osrm'] == (cause if cause == 'OSRM_UNAVAILABLE' else 'NOT_CALLED'), 'adverse setup')
        if cause == 'QWEN_CAPACITY': http,status = 429,None
    else:
        require(s['provider']['cause'] is None and s['provider']['qwen'] == 'VALID_SEQUENCE'
                and s['provider']['osrm'] == 'VERIFIED', 'unexpected provider setup')
    if category == 'INVALID_OR_ACCESS':
        if case['actor'] != 'USER':
            http = 401 if case['actor'] == 'GUEST' else 403
            require(minutes(inp['startTime']) < minutes(inp['endTime']), 'role case must use valid input')
        else:
            http = 400
            try: invalid = minutes(inp['startTime']) >= minutes(inp['endTime'])
            except ValueError: invalid = True
            require(invalid, 'validation case must be invalid')
    else:
        require(case['actor'] == 'USER' and minutes(inp['startTime']) < minutes(inp['endTime']), 'valid itinerary input')
    require(type(exp['httpStatus']) is int and exp['httpStatus'] == http and exp['status'] == status and oracle['type'] == kind, 'itinerary status/type')
    if category != 'FEASIBLE':
        require(not oracle['witnessOrders'] and not proof['witnessSchedules'], 'unexpected witness')
    if category != 'INFEASIBLE': require(not proof['rejectedOrders'], 'unexpected rejected orders')
    if category != 'NEEDS_DATA': require(not proof['missingFacts'], 'unexpected missing facts')
    if category == 'NEEDS_DATA':
        missing = []
        for l in s['locations']:
            if l['metadata'] is None: missing.append(l['id']+'.metadata')
            elif l['metadata']['durationMinutes'] is None: missing.append(l['id']+'.durationMinutes')
            elif not intervals(l,trip): missing.append(l['id']+'.hours')
        if len(candidates) < 2 and inp['startLocationId'] in candidates: missing.append('minimum_two_candidates')
        require(unique(proof['missingFacts']) == set(missing) and missing, 'missing facts proof')
    if category == 'FEASIBLE':
        require(oracle['witnessOrders'] and len(oracle['witnessOrders']) == len(proof['witnessSchedules']), 'witness coverage')
        for order, witness in zip(oracle['witnessOrders'],proof['witnessSchedules']):
            keys(witness, {'order','items'}, 'witness')
            chosen = unique(order)
            require(2 <= len(order) <= 4 and order[0] == inp['startLocationId'] and chosen <= candidates
                    and required <= chosen and not forbidden & chosen and witness['order'] == order, 'witness order')
            failure, items = schedule(order,s,inp)
            require(failure is None and json.dumps(witness['items'], sort_keys=True) == json.dumps(items, sort_keys=True), 'witness schedule mismatch')
    if category == 'INFEASIBLE':
        others = sorted(candidates-{inp['startLocationId']})
        all_orders = [(inp['startLocationId'],)+p for size in range(1,min(3,len(others))+1) for p in permutations(others,size)]
        rejected = {}
        for row in proof['rejectedOrders']:
            keys(row, {'order','cause'}, 'rejected order'); unique(row['order'])
            order = tuple(row['order']); require(order not in rejected, 'duplicate rejected order')
            rejected[order] = row['cause']
        require(all_orders and set(rejected) == set(all_orders), 'exhaustive scope incomplete')
        for order in all_orders:
            cause,_ = schedule(order,s,inp)
            require(cause is not None and cause == rejected[order], 'false infeasible/cause')


def validate(root):
    root = Path(root).resolve()
    manifest = decode((root/'manifest.json').read_text(encoding='utf-8'))
    keys(manifest, {'schemaVersion','datasetVersion','datasets','profiles','reviewState'}, 'manifest')
    require(manifest['schemaVersion'] == VERSION and manifest['datasetVersion'] and manifest['reviewState'] in ('DRAFT','REVIEWED'), 'manifest version/state')
    require(len(manifest['datasets']) == 2 and isinstance(manifest['profiles'],dict), 'manifest datasets/profiles')
    profiles = manifest['profiles']; facts = None
    for ref,p in profiles.items():
        require(ID.fullmatch(ref), 'profile ID')
        keys(p, {'clockInstant','timezone','bindings','factsPath','factsSha256','providerMode'}, 'profile')
        require(p['clockInstant'] == '2027-02-01T00:00:00Z' and p['timezone'] == 'Asia/Ho_Chi_Minh'
                and p['bindings'] == {} and p['factsPath'] == 'facts.json' and p['providerMode'] in ('synthetic','adverse'), 'fixture profile')
        path = checked_json(root,p['factsPath'],p['factsSha256'])
        if facts is None: facts = decode(path.read_text(encoding='utf-8'))
    require(facts is not None, 'facts required')
    keys(facts, {'schemaVersion','synthetic','branches','routes','vehicles','scenarios'}, 'facts')
    require(facts['schemaVersion'] == VERSION and facts['synthetic'] is True, 'synthetic facts required')
    branches = unique([b['id'] for b in facts['branches']])
    for b in facts['branches']:
        keys(b, {'id','status'}, 'branch'); require(b['status'] in ('ACTIVE','INACTIVE'), 'branch status')
    pairs = set()
    for route in facts['routes']:
        keys(route, {'pickup','returned','fee'}, 'route'); integer(route['fee'])
        pair = route['pickup'],route['returned']
        require(set(pair) <= branches and pair not in pairs, 'route IDs'); pairs.add(pair)
    unique([v['id'] for v in facts['vehicles']])
    for v in facts['vehicles']:
        keys(v, {'id','name','seats','dailyPrice','transmission','amenityIds','currentBranchId','status'}, 'vehicle')
        integer(v['seats'],2,9); integer(v['dailyPrice'],1); unique(v['amenityIds'])
        require(v['status'] in ('ACTIVE','MAINTENANCE') and v['transmission'] in ('AUTO','MANUAL')
                and v['currentBranchId'] in branches and v['name'], 'vehicle facts')
    ids = set(); suites = set(); counts = {}; access = Counter(); causes = set()
    for entry in manifest['datasets']:
        keys(entry, {'path','sha256','suite','split','count'}, 'dataset entry')
        suite = entry['suite']
        require(suite in QUOTAS and suite not in suites and entry['split'] == 'dev' and type(entry['count']) is int and entry['count'] == 30, 'suite/split/count')
        suites.add(suite)
        lines = checked_json(root,entry['path'],entry['sha256']).read_text(encoding='utf-8').splitlines()
        require(len(lines) == 30 and all(lines), '30 JSONL lines')
        categories = Counter()
        for line in lines:
            c = decode(line); keys(c,COMMON,'case')
            cid = c['caseId']; require(isinstance(cid,str) and ID.fullmatch(cid) and cid not in ids, 'case ID')
            ids.add(cid)
            require(c['schemaVersion'] == VERSION and c['suite'] == suite and c['split'] == 'dev'
                    and ID.fullmatch(c['groupId']) and c['category'] in QUOTAS[suite]
                    and c['actor'] in ('USER','GUEST','STAFF','ADMIN'), 'case envelope')
            require('synthetic' in unique(c['tags']) and c['fixtureRef'] == cid and cid in profiles and cid in facts['scenarios'], 'case profile/tags')
            label=c['label']; keys(label, {'rationale','author','reviewer','reviewState'}, 'label')
            require(all(isinstance(label[k],str) and label[k].strip() for k in ('author','rationale')), 'label rationale/author')
            require(label['reviewState'] == manifest['reviewState'], 'review state mismatch')
            require((label['reviewState'] == 'DRAFT' and label['reviewer'] is None) or
                    (label['reviewState'] == 'REVIEWED' and isinstance(label['reviewer'],str)
                     and label['reviewer'].strip() and label['reviewer'] != label['author']), 'independent reviewer required')
            p=profiles[cid]; s=facts['scenarios'][cid]
            require(s['suite'] == suite and p['providerMode'] == ('adverse' if c['category'] == 'PROVIDER_ADVERSE' else 'synthetic'), 'profile mode/suite')
            clock=datetime.fromisoformat(p['clockInstant'].replace('Z','+00:00'))
            if suite == 'recommendation': recommendation(c,s,facts,clock)
            else:
                itinerary(c,s,clock)
                if c['category'] == 'INVALID_OR_ACCESS': access[c['actor']]+=1
                if c['category'] == 'PROVIDER_ADVERSE': causes.add(s['provider']['cause'])
            categories[c['category']]+=1
        require(dict(categories) == QUOTAS[suite], 'category quota')
        counts[suite]=dict(categories)
    require(ids == set(profiles) == set(facts['scenarios']), 'orphan/missing profile')
    require(dict(access) == {'USER':2,'GUEST':1,'STAFF':1,'ADMIN':1} and len(causes) == 5, 'access/adverse coverage')
    return dict(datasetVersion=manifest['datasetVersion'],reviewState=manifest['reviewState'],cases=len(ids),counts=counts,
                scope='offline dataset integrity and synthetic oracle only; no API/model quality result')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).parent/'scenarios-v1')
    args=parser.parse_args()
    try: print(json.dumps(validate(args.root),ensure_ascii=False))
    except (ValueError,OSError,TypeError,KeyError,IndexError,AttributeError) as error:
        parser.exit(2,'DATASET_INVALID: '+str(error)+'\n')


if __name__ == '__main__':
    main()
