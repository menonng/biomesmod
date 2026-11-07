"""Pairwise feature-order inversions between our biomes and other mods' biomes (same step, same two features, opposite order).
usage: ordercheck.py <our biome dir> <jar or dir>..."""
import json, os, sys, zipfile, itertools
def load_dir(d):
    out={}
    for f in os.listdir(d):
        if f.endswith('.json'): out['biomesmod:'+f[:-5]]=json.load(open(os.path.join(d,f),encoding='utf-8'))
    return out
def load_jar(p):
    out={}
    if p.endswith('.jar') or p.endswith('.zip'):
        z=zipfile.ZipFile(p)
        for n in z.namelist():
            parts=n.split('/')
            if len(parts)>=5 and parts[0]=='data' and parts[2:4]==['worldgen','biome'] and n.endswith('.json'):
                try: out[parts[1]+':'+'/'.join(parts[4:])[:-5]]=json.loads(z.read(n))
                except Exception: pass
    return out
ours=load_dir(sys.argv[1])
theirs={}
for p in sys.argv[2:]: theirs.update(load_jar(p))
def pairs(feats):
    pos={}
    for step,lst in enumerate(feats):
        for i,f in enumerate(lst):
            if isinstance(f,str): pos.setdefault(step,{})[f]=i
    return pos
bad={}
for on,ob in ours.items():
    po=pairs(ob.get('features',[]))
    for tn,tb in theirs.items():
        pt=pairs(tb.get('features',[]))
        for step in po:
            if step not in pt: continue
            common=[f for f in po[step] if f in pt[step]]
            for a,b in itertools.combinations(common,2):
                if (po[step][a]<po[step][b]) != (pt[step][a]<pt[step][b]):
                    first,second=(a,b) if pt[step][a]<pt[step][b] else (b,a)
                    bad.setdefault((step,first,second),set()).add(on+' vs '+tn)
for (step,a,b),who in sorted(bad.items()):
    print('step %d: they want %s before %s  (%d pair(s), e.g. %s)'%(step,a,b,len(who),sorted(who)[0]))
print(len(theirs),'foreign biomes checked;',len(bad),'inverted pair(s)')
