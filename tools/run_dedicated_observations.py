#!/usr/bin/env python3
"""Bridge guarded local dedicated-test stages to two independent game clients.

Does not start Minecraft, accept EULA, grant permissions, or create game data.
Run with three fresh project profiles while their test-only helpers are active.
"""
from pathlib import Path
import argparse
import json
import sys
import time
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('server')
parser.add_argument('owner')
parser.add_argument('foreign')
args = parser.parse_args()
(server, owner, foreign) = [(ROOT / n).resolve() for n in (args.server, args.owner, args.foreign)]
if len({server, owner, foreign}) != 3:
    raise ValueError('Use three distinct test profiles')
profiles = {'owner': owner, 'foreign': foreign}

def read(p):
    try:
        return json.loads(p.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None
manifests = {}
for p in [server, owner, foreign]:
    if not p.is_relative_to(ROOT) or p.is_relative_to(ROOT / 'instance'):
        raise ValueError('Use separate project profiles, outside instance')
    m = read(p / 'test-profile.json')
    if not m or m.get('worldsCopied') is not False or (not m.get('testHelpers')) or m.get('baselineOnly'):
        raise ValueError('Use fresh integration profiles with the explicit test helper')
    manifests[p] = m
run = manifests[server]['runId']
sequences = {r: 0 for r in profiles}
for (role, p) in profiles.items():
    old = read(p / 'pis1-network-control.json')
    if old:
        sequences[role] = old['sequence']

def write(p, d):
    tmp = p.with_suffix(p.suffix + '.bridge-tmp')
    tmp.write_text(json.dumps(d))
    tmp.replace(p)

def command(role, fields):
    sequences[role] += 1
    c = {**fields, 'runId': manifests[profiles[role]]['runId'], 'sequence': sequences[role], 'role': role}
    c.setdefault('guiScale', 2)
    c.setdefault('settleTicks', 40)
    c.setdefault('timeoutTicks', 1200)
    write(profiles[role] / 'pis1-network-control.json', c)
    return c['sequence']

def wait(roles, ids, timeout=130):
    out = {}
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for r in roles:
            d = read(profiles[r] / 'pis1-network-observation.json')
            if d and d.get('sequence') == ids[r] and d.get('completed'):
                out[r] = d
        if len(out) == len(roles):
            for (r, d) in out.items():
                print(json.dumps({'role': r, 'sequence': d['sequence'], 'passed': d['passed'], 'phase': d['phase'], 'status': d['status']}, ensure_ascii=False), flush=True)
            if any((not d['passed'] for d in out.values())):
                raise RuntimeError('Actual client observation failed')
            return out
        time.sleep(0.5)
    raise TimeoutError('Client observation timeout: ' + ','.join(set(roles) - set(out)))
last = 0
deadline = time.monotonic() + 1800
while time.monotonic() < deadline:
    stage = read(server / 'pis1-dedicated-stage.json')
    if not stage or stage.get('sequence', 0) <= last:
        result = read(server / 'pis1-dedicated-checks.json')
        if result and result.get('completedAt'):
            if result.get('runId') != run:
                raise ValueError('Dedicated report runId does not match profile')
            passed = result.get('passed') is True
            print('DEDICATED_REPORT ' + str(passed), flush=True)
            sys.exit(0 if passed else 1)
        time.sleep(0.5)
        continue
    if stage['runId'] != run:
        raise ValueError('Dedicated stage runId does not match profile')
    n = stage['sequence']
    print('STAGE ' + json.dumps(stage, ensure_ascii=False), flush=True)
    if stage.get('requiresReconnect'):
        roles = ['foreign']
        ids = {r: command(r, {'action': 'disconnect', 'phase': stage['phase'] + '-disconnect', 'kind': 'world'}) for r in roles}
        wait(roles, ids)
        time.sleep(2)
        ids = {r: command(r, {'action': 'reconnect', 'phase': stage['phase'] + '-connect', 'kind': 'world'}) for r in roles}
        wait(roles, ids)
        time.sleep(3)
    fields = {k: v for (k, v) in stage.items() if k not in {'runId', 'sequence', 'requiresReconnect'}}
    fields['action'] = 'observe'
    fields.setdefault('kind', 'world')
    fields.setdefault('capture', True)
    ids = {r: command(r, fields) for r in profiles}
    results = wait(list(profiles), ids)
    ack = {'runId': run, 'sequence': n, 'proceed': True, 'ownerPassed': True, 'foreignPassed': True, 'evidence': results}
    write(server / 'pis1-dedicated-ack.json', ack)
    last = n
    print('ACK ' + str(n), flush=True)
raise TimeoutError('Dedicated stage bridge exceeded 30 minutes')
