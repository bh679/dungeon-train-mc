# Nap sky geometry

`src/main/resources/assets/dungeontrain/nap/sky.json` is the shape of the perks constellation, drawn
behind the nap farewell's "a life ongoing" page (`client/nap/NapSky*`). It holds positions and the
solid (requirement) lines only: no perk ids, names or descriptions, so the page hints at the map
without showing a perk.

- `stars`: `[x, y, tier]` in the editor's 800 x 800 space; index 0 is the Engine at the centre.
- `lines`: `[a, b]` star indices joined by a requirement line.

When the perks map changes on `dev/perks` (or main, once perks land), regenerate it:

```bash
git show origin/dev/perks:src/main/resources/data/dungeontrain/perks/tree.json | python3 -c "
import json,sys;t=json.load(sys.stdin);ps=t['perks'];idx={p['id']:i for i,p in enumerate(ps)}
assert ps[0]['id']=='root'
out={'source':'dev/perks data/dungeontrain/perks/tree.json v%d (geometry only)'%t['v'],'stars':[[round(p['x'],1),round(p['y'],1),p.get('tier') or 0] for p in ps],'lines':[[idx[l['a']],idx[l['b']]] for l in t['links'] if l['kind']=='req' and l['a'] in idx and l['b'] in idx]}
print(json.dumps(out,separators=(',',':')))" > src/main/resources/assets/dungeontrain/nap/sky.json
```

Where perks are on, a past life with a perk record draws the perks it activated, and this life draws the
perks activated so far (`NapSkyLives`, `NapSkyPaths#fromStars`). A perk is matched to its star by
position (`NapSkyStars`), so **regenerate this file whenever `tree.json` positions change**:
`NapSkyLivesTest#everyPerkHasAStar` fails until you do. Lives from before perks, and every life while
perks are off, still get generated paths (`NapSkyPaths#pastLife`).
