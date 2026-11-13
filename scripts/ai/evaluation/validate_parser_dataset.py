"""Read-only parser dataset checks. No model, HTTP, database or scoring calls."""
import argparse
from collections import Counter
from datetime import date, datetime
import hashlib
import json
from pathlib import Path
import re
import unicodedata

VERSION = 'kdrive-ai-eval-v1'
QUOTAS = {'CLEAR': 50, 'CLARIFY': 20, 'CONFLICT': 10, 'OUT_OF_SCOPE': 10, 'PRIVACY_INJECTION': 10}
FIELDS = {'pickupDate', 'returnDate', 'pickupBranchId', 'returnBranchId', 'passengers', 'budgetVndTotal', 'transmission'}
MISSING = {'pickupDate', 'returnDate', 'pickupBranchName', 'returnBranchName', 'passengers', 'budgetVndTotal'}
COMMON = {'schemaVersion', 'caseId', 'suite', 'split', 'category', 'groupId', 'tags', 'fixtureRef', 'actor', 'input', 'expected', 'label'}
ID = re.compile(r'[a-z0-9][a-z0-9_-]{0,63}')
TOKEN = re.compile(r'\{\{([a-zA-Z][a-zA-Z0-9_]*)\}\}')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def keys(value, expected, context):
    require(isinstance(value, dict) and set(value) == expected, context + ': fields')


def pairs(items):
    result = {}
    for name, value in items:
        require(name not in result, 'duplicate JSON key: ' + name)
        result[name] = value
    return result


def decode(text):
    def bad_constant(value):
        raise ValueError('non-finite JSON: ' + value)
    return json.loads(text, object_pairs_hook=pairs, parse_constant=bad_constant)


def local_file(root, relative):
    require(isinstance(relative, str) and bool(relative) and '\\' not in relative, 'invalid relative path')
    part = Path(relative)
    require(not part.is_absolute() and '..' not in part.parts and ':' not in relative, 'unsafe relative path')
    path = (root / part).resolve()
    require(path.is_relative_to(root) and path.is_file(), 'file outside root or missing')
    return path


def checked_json(root, relative, expected_hash):
    path = local_file(root, relative)
    require(isinstance(expected_hash, str) and re.fullmatch('[a-f0-9]{64}', expected_hash), 'invalid SHA256')
    require(hashlib.sha256(path.read_bytes()).hexdigest() == expected_hash, 'hash mismatch: ' + relative)
    return path


def render(value, bindings):
    if isinstance(value, dict):
        return {k: render(v, bindings) for k, v in value.items()}
    if isinstance(value, list):
        return [render(v, bindings) for v in value]
    if not isinstance(value, str):
        return value
    def binding(name):
        require(name in bindings, 'missing binding: ' + name)
        return bindings[name]
    full = TOKEN.fullmatch(value)
    if full:
        return binding(full[1])
    rendered = TOKEN.sub(lambda match: str(binding(match[1])), value)
    require('{{' not in rendered and '}}' not in rendered, 'malformed placeholder')
    return rendered


def profile_data(root, profile):
    keys(profile, {'clockInstant', 'timezone', 'bindings', 'factsPath', 'factsSha256', 'providerMode'}, 'profile')
    require(profile['providerMode'] == 'synthetic' and profile['timezone'] == 'Asia/Ho_Chi_Minh', 'synthetic profile/timezone required')
    clock = datetime.fromisoformat(profile['clockInstant'].replace('Z', '+00:00'))
    require(clock.utcoffset() is not None and clock.utcoffset().total_seconds() == 0, 'UTC clock required')
    bindings = profile['bindings']
    require(isinstance(bindings, dict) and bindings and all(re.fullmatch('[a-zA-Z][a-zA-Z0-9_]*', k) and type(v) in (str, int) for k, v in bindings.items()), 'invalid bindings')
    facts = decode(checked_json(root, profile['factsPath'], profile['factsSha256']).read_text(encoding='utf-8'))
    keys(facts, {'schemaVersion', 'branches'}, 'facts')
    require(facts['schemaVersion'] == VERSION and isinstance(facts['branches'], list) and facts['branches'], 'facts version/branches')
    ids = set()
    for branch in facts['branches']:
        keys(branch, {'id', 'name', 'status'}, 'branch')
        require(isinstance(branch['id'], str) and 1 <= len(branch['id']) <= 36 and branch['id'] not in ids, 'duplicate/invalid branch ID')
        require(isinstance(branch['name'], str) and branch['name'].strip() and branch['status'] in ('ACTIVE', 'INACTIVE'), 'branch name/status')
        ids.add(branch['id'])
    active = {b['id'] for b in facts['branches'] if b['status'] == 'ACTIVE'}
    return bindings, active, clock.date()


def variant_check(variant, category, active, clock_date):
    keys(variant, {'httpStatus', 'status', 'draft', 'unresolvedFields'}, 'variant')
    require(type(variant['httpStatus']) is int and variant['httpStatus'] in (200, 400), 'invalid parser HTTP status')
    if variant['httpStatus'] == 400:
        require(category == 'PRIVACY_INJECTION' and all(variant[k] is None for k in ('status', 'draft', 'unresolvedFields')), 'guard error label')
        return
    status, draft, unresolved = variant['status'], variant['draft'], variant['unresolvedFields']
    require(isinstance(unresolved, list) and all(isinstance(f, str) for f in unresolved) and len(set(unresolved)) == len(unresolved), 'unresolved list')
    if category == 'CONFLICT':
        require(status == 'NEEDS_CLARIFICATION' and draft is None and unresolved == ['conflicts'], 'conflict label')
        return
    if status == 'OUT_OF_SCOPE':
        require(category in ('OUT_OF_SCOPE', 'PRIVACY_INJECTION') and draft is None and not unresolved, 'out-of-scope label')
        return
    require(category in ('CLEAR', 'CLARIFY', 'PRIVACY_INJECTION'), 'category/status mismatch')
    keys(draft, FIELDS, 'draft')
    missing = set()
    for field in FIELDS - {'transmission'}:
        value = draft[field]
        if value is None:
            missing.add({'pickupBranchId': 'pickupBranchName', 'returnBranchId': 'returnBranchName'}.get(field, field))
        elif field.endswith('Date'):
            require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}', value), 'ISO date required')
            require(date.fromisoformat(value) > clock_date, 'date must follow fixture clock')
        elif field.endswith('BranchId'):
            require(isinstance(value, str) and value in active, 'branch must be active fixture ID')
        elif field == 'passengers':
            require(type(value) is int and 2 <= value <= 9, 'passengers integer/range')
        else:
            require(type(value) is int and 0 <= value <= 9007199254740991, 'budget integer/range')
    if draft['pickupDate'] is not None and draft['returnDate'] is not None:
        require(draft['pickupDate'] < draft['returnDate'], 'return date must follow pickup')
    require(draft['transmission'] in (None, 'AUTO', 'MANUAL'), 'transmission enum')
    require(set(unresolved) == missing and missing <= MISSING, 'missing fields differ from draft')
    require(status == ('NEEDS_CLARIFICATION' if missing else 'READY_FOR_REVIEW'), 'status completeness')
    require((category == 'CLARIFY') == bool(missing), 'category completeness')


def validate(root):
    root = Path(root).resolve()
    manifest = decode((root / 'manifest.json').read_text(encoding='utf-8'))
    keys(manifest, {'schemaVersion', 'datasetVersion', 'datasets', 'profiles', 'reviewState'}, 'manifest')
    require(manifest['schemaVersion'] == VERSION and isinstance(manifest['datasetVersion'], str) and manifest['datasetVersion'].strip(), 'manifest version')
    require(manifest['reviewState'] in ('DRAFT', 'REVIEWED'), 'review state')
    require(isinstance(manifest['profiles'], dict) and manifest['profiles'], 'profiles required')
    profiles = {name: profile_data(root, profile) for name, profile in manifest['profiles'].items()}
    require(isinstance(manifest['datasets'], list) and len(manifest['datasets']) == 2, 'two splits required')
    groups, texts, ids, splits, counts = {}, set(), set(), set(), {}
    for entry in manifest['datasets']:
        keys(entry, {'path', 'sha256', 'suite', 'split', 'count'}, 'dataset entry')
        split = entry['split']
        require(entry['suite'] == 'parser' and split in ('dev', 'heldout') and split not in splits and type(entry['count']) is int and entry['count'] == 100, 'split/count')
        splits.add(split)
        raw = checked_json(root, entry['path'], entry['sha256']).read_text(encoding='utf-8')
        lines = raw.splitlines()
        require(len(lines) == 100 and all(line.strip() for line in lines), '100 nonempty JSONL lines required')
        categories = Counter()
        guard = injection = 0
        for line in lines:
            case = decode(line)
            keys(case, COMMON, 'case')
            cid = case['caseId']
            require(isinstance(cid, str) and ID.fullmatch(cid) and cid not in ids, 'case ID duplicate/invalid')
            ids.add(cid)
            group = case['groupId']
            require(isinstance(group, str) and ID.fullmatch(group), 'group ID')
            require(groups.setdefault(group, split) == split, 'cross-split group leakage')
            require(case['schemaVersion'] == VERSION and case['suite'] == 'parser' and case['split'] == split and case['actor'] == 'USER', 'case envelope')
            category = case['category']
            require(category in QUOTAS and case['fixtureRef'] in profiles, 'category/fixture')
            tags = case['tags']
            require(isinstance(tags, list) and all(isinstance(t, str) and t for t in tags) and len(set(tags)) == len(tags) and 'synthetic' in tags, 'tags')
            keys(case['label'], {'rationale', 'author', 'reviewer', 'reviewState'}, 'label')
            label = case['label']
            require(all(isinstance(label[k], str) and label[k].strip() for k in ('rationale', 'author')), 'rationale/author')
            require(label['reviewState'] == manifest['reviewState'], 'review state mismatch')
            require((label['reviewState'] == 'DRAFT' and label['reviewer'] is None) or (label['reviewState'] == 'REVIEWED' and isinstance(label['reviewer'], str) and label['reviewer'].strip() and label['reviewer'] != label['author']), 'independent reviewer required')
            bindings, active, clock_date = profiles[case['fixtureRef']]
            keys(case['input'], {'text'}, 'input')
            text = render(case['input']['text'], bindings)
            require(isinstance(text, str) and 1 <= len(text) <= 1000, 'input text length')
            normalized = ' '.join(unicodedata.normalize('NFC', text).split())
            require(normalized not in texts, 'normalized duplicate text')
            texts.add(normalized)
            expected = render(case['expected'], bindings)
            keys(expected, {'kind', 'variants'}, 'expected')
            require(expected['kind'] == 'PARSER' and isinstance(expected['variants'], list) and 1 <= len(expected['variants']) <= 3, 'parser variants')
            for variant in expected['variants']:
                variant_check(variant, category, active, clock_date)
            if category == 'PRIVACY_INJECTION':
                is_guard = 'guard_before_inference' in tags
                require(is_guard != ('prompt_injection' in tags), 'guard/injection exclusive tags')
                require(all((v['httpStatus'] == 400) == is_guard for v in expected['variants']), 'guard status')
                guard += is_guard
                injection += not is_guard
            categories[category] += 1
        require(dict(categories) == QUOTAS and guard == injection == 5, 'category/guard/injection quotas')
        counts[split] = dict(categories)
    return {'datasetVersion': manifest['datasetVersion'], 'reviewState': manifest['reviewState'], 'cases': len(ids), 'groups': len(groups), 'counts': counts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).parent / 'parser-v1')
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.root), ensure_ascii=False))
    except (ValueError, OSError, TypeError, KeyError) as error:
        parser.exit(2, 'DATASET_INVALID: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
