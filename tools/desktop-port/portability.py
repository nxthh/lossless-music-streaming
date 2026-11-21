#!/usr/bin/env python3
"""
Portability report for the Immersive UI -> shared KMP module (:immersive). See DESKTOP.md.

  python3 tools/desktop-port/portability.py            # report on what is still in :app
  python3 tools/desktop-port/portability.py --movable  # only the files that can move right now

"Movable" = under ui/immersive/ in :app, no Android / app-layer imports, and every file it
references (same package needs no import in Kotlin, so references are matched by identifier)
is also movable or already moved. It is a greatest fixed point, and deliberately pessimistic:
identifier matching over-links (a shared name such as `Blur` links two files that are unrelated),
so a file reported as blocked may be fine; check by hand before giving up on it.
Static analysis only: nothing here proves a file compiles in commonMain.
"""
import os, re, sys, collections
HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.normpath(os.path.join(HERE, '..', '..', 'base'))
ROOT = os.path.join(BASE, 'app/src/main/java').replace(os.sep, '/')
IM = '/ui/immersive/'
HARD = re.compile(r'^(android\.|androidx\.(core|media3|webkit|activity|work|appcompat|palette|navigation|lifecycle)|app\.myspoti\.|com\.bumptech|java\.|javax\.|org\.intellij)')
APPL = re.compile(r'^com\.music\.spotui\.(?!ui\.immersive)')
DECL = re.compile(r'^\s*(?:(?:public|private|internal|protected|data|sealed|enum|abstract|open|inline|value|annotation|const|lateinit|override|suspend|operator|infix|tailrec|expect|actual)\s+)*(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?`?([A-Za-z_][A-Za-z0-9_]*)', re.M)
ID = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')

info, byname = {}, collections.defaultdict(set)
for dp, _, fs in os.walk(ROOT):
    for f in fs:
        if not f.endswith('.kt'): continue
        p = os.path.join(dp, f).replace(os.sep, '/'); s = open(p, encoding='utf-8', errors='ignore').read()
        pkg = (re.search(r'^package\s+([\w.]+)', s, re.M) or [None, ''])[1]
        info[p] = dict(pkg=pkg, src=s, imps=re.findall(r'^import\s+([\w.*]+)', s, re.M), names=set(DECL.findall(s)))
        for n in info[p]['names']: byname[(pkg, n)].add(p)

def deps(p):
    d, out, ids = info[p], set(), set(ID.findall(info[p]['src']))
    for i in d['imps']:
        if not i.startswith(('com.music.spotui', 'app.myspoti')): continue
        if i.endswith('.*'):
            for n in ids: out |= byname.get((i[:-2], n), set())
        else:
            pk, _, n = i.rpartition('.'); out |= byname.get((pk, n), set())
            pk2, _, o = pk.rpartition('.'); out |= byname.get((pk2, o), set())
    for n in ids: out |= byname.get((d['pkg'], n), set())
    out.discard(p); return out

def blockers(p): return [i for i in info[p]['imps'] if HARD.match(i) or APPL.match(i)]
lines = lambda p: info[p]['src'].count('\n') + 1
rel = lambda p: os.path.relpath(p, ROOT + '/com/music/spotui').replace(os.sep, '/')

imm = [p for p in info if IM in p]
cand = {p for p in imm if not blockers(p)}
changed = True
while changed:
    changed = False
    for p in list(cand):
        if any(d not in cand for d in deps(p)): cand.discard(p); changed = True

print(f"Immersive files still in :app: {len(imm)} ({sum(map(lines, imm))} lines)")
print(f"Movable right now: {len(cand)} files ({sum(map(lines, cand))} lines)")
for p in sorted(cand): print(f"  {lines(p):5} {rel(p)}")
if '--movable' in sys.argv: sys.exit()

print("\nMost common direct blockers (count of files):")
c = collections.Counter(b for p in imm for b in set(blockers(p)))
for b, n in c.most_common(40): print(f"  {n:3} {b}")
print("\nFiles by number of direct blockers (fewest first; the cheapest to unlock):")
for p in sorted(imm, key=lambda p: len(blockers(p)))[:40]:
    print(f"  {len(blockers(p)):3} blockers  {lines(p):5} lines  {rel(p)}")
