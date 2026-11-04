#!/usr/bin/env python3
"""Author data/dungeontrain/portals/room/home.nbt: the room every home room starts as.

Box 11 x 7 x 13 (the built-in portal room box). Entry door on the -X wall, exit on +X, both centred.
A 3x2 Vista TV on the north wall faces south, tuned with the Home Cassette; chests along the south
wall. Run from the repo root; the NBT helpers live beside this script.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt
from nbt import S, I, C, L, IA

L_, H_, W_ = 11, 7, 13
OUT = 'src/main/resources/data/dungeontrain/portals/room/home.nbt'

palette = []
def pal(name, props=None):
    key = (name, tuple(sorted((props or {}).items())))
    for i, (k, _) in enumerate(palette):
        if k == key: return i
    entry = {'Name': S(name)}
    if props: entry['Properties'] = C({k: S(v) for k, v in props.items()})
    palette.append((key, entry)); return len(palette) - 1

blocks = {}
def put(x, y, z, idx, be=None):
    b = {'pos': L(3, [x, y, z]), 'state': I(idx)}
    if be: b['nbt'] = C(be)
    blocks[(x, y, z)] = b

wall = pal('minecraft:deepslate_bricks'); floor = pal('minecraft:dark_oak_planks'); ceil = pal('minecraft:deepslate_tiles')
for x in range(L_):
    for y in range(H_):
        for z in range(W_):
            shell = x in (0, L_-1) or z in (0, W_-1) or y in (0, H_-1)
            if not shell: continue
            put(x, y, z, floor if y == 0 else ceil if y == H_-1 else wall)
# doorways: 3 wide, 3 tall, centred on z=6, both X walls
for x in (0, L_-1):
    for y in (1, 2, 3):
        for z in (5, 6, 7):
            blocks.pop((x, y, z), None)
# lanterns
lan = pal('minecraft:lantern', {'hanging': 'true', 'waterlogged': 'false'})
for (x, z) in ((3, 3), (7, 3), (3, 9), (7, 9)): put(x, 5, z, lan)
# chests on the south wall, facing north
chest = pal('minecraft:chest', {'facing': 'north', 'type': 'single', 'waterlogged': 'false'})
for x in (3, 5, 7): put(x, 1, 11, chest, {'id': S('minecraft:chest'), 'Items': L(10, [])})
put(9, 1, 3, pal('minecraft:crafting_table'))
put(9, 1, 9, pal('minecraft:barrel', {'facing': 'up', 'open': 'false'}), {'id': S('minecraft:barrel'), 'Items': L(10, [])})
# the TV: 3 wide (x 4..6) x 2 tall (y 2..3) on the north wall (z=1), screen facing south
conn = {(4, 2): 'bottom_left', (5, 2): 'bottom', (6, 2): 'bottom_right', (4, 3): 'top_left', (5, 3): 'top', (6, 3): 'top_right'}
for (x, y), c in conn.items():
    powered = 'direct' if (x, y) == (5, 2) else 'indirect'
    idx = pal('vista:television', {'connection': c, 'facing': 'south', 'powered': powered})
    be = None
    if c == 'bottom_left':
        be = {'id': S('vista:television'), 'ConnectionWidth': I(3), 'ConnectionHeight': I(2), 'ShowsTime': (1, 0),
              'HasEnergy': (1, 0), 'HasSpeaker': (1, 0), 'Paused': (1, 0), 'VideoPlaybackTicks': I(0),
              'Items': L(10, [{'Slot': (1, 0), 'id': S('dungeontrain:home_cassette'), 'count': I(1)}])}
    put(x, y, 1, idx, be)
# a stand mat in front of the TV so the arrival spot reads as one
mat = pal('minecraft:red_carpet')
put(5, 1, 3, mat)

root = {'size': L(3, [L_, H_, W_]), 'entities': L(10, []),
        'blocks': L(10, [blocks[k] for k in sorted(blocks)]),
        'palette': L(10, [e for _, e in palette]), 'DataVersion': I(3955)}
# The bundled room is authored in the Portals editor from here on: never overwrite one that exists.
if os.path.exists(OUT) and '--force' not in sys.argv:
    print('kept', OUT, '(authored; pass --force to regenerate)')
else:
    nbt.write(OUT, root)
    print('wrote', OUT, len(blocks), 'blocks,', len(palette), 'palette entries')


def tube(name, open_at_high_x):
    """A 16 x 7 x 9 black concrete passage: closed at one end, open to the room at the other.

    The death visit stands these end-on against the home room's doors (HomeRoomCorridors). They
    are portal-room templates so the Portals editor lists them under `home`; weight 0 keeps the
    train lottery from ever standing one as a room."""
    TL, TH, TW = 16, 7, 9
    pal2 = []
    def p2(n):
        for i, (k, _) in enumerate(pal2):
            if k == n: return i
        pal2.append((n, {'Name': S(n)})); return len(pal2) - 1
    con = p2('minecraft:black_concrete'); air = None
    bl = {}
    for x in range(TL):
        for y in range(TH):
            for z in range(TW):
                shell = y in (0, TH - 1) or z in (0, TW - 1)
                closed_end = (x == TL - 1) if not open_at_high_x else (x == 0)
                if shell or closed_end:
                    bl[(x, y, z)] = {'pos': L(3, [x, y, z]), 'state': I(con)}
    out = f'src/main/resources/data/dungeontrain/portals/room/{name}.nbt'
    if os.path.exists(out) and '--force' not in sys.argv:
        print('kept', out, '(authored; pass --force to regenerate)'); return
    nbt.write(out, {'size': L(3, [TL, TH, TW]), 'entities': L(10, []),
                    'blocks': L(10, [bl[k] for k in sorted(bl)]),
                    'palette': L(10, [e for _, e in pal2]), 'DataVersion': I(3955)})
    print('wrote', out, len(bl), 'blocks')


# The entrance opens toward +x (the room sits past its high end); the exit opens toward -x.
tube('home_entrance', True)
tube('home_exit', False)
import json
group = {"schemaVersion": 3, "selfWeight": 1,
         "variants": [{"id": "home_entrance", "weight": 0}, {"id": "home_exit", "weight": 0}]}
gp = 'src/main/resources/data/dungeontrain/portals/room/home.group.json'
with open(gp, 'w') as fh:
    json.dump(group, fh, indent=2); fh.write('\n')
print('wrote', gp)
