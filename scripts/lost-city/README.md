# Lost City — Dungeon Train's own buildings

The Lost City band places two kinds of building:

- **Big Lost City's** (`big_lost_city:*`), a required third-party mod that is All Rights Reserved. DT
  places its templates exactly as shipped and never attaches a DT processor to one — recolouring,
  stretching or biting them would be a derivative work we cannot publish.
- **DT's own** (`dungeontrain:lost_city/<name>`), designed and generated here, and the only ones the
  variation processors (`lost_city_swap`, `lost_city_stretch`, `lost_city_bite`, `lost_city_facade`,
  `lost_city_truncate`) run on.

`LostCityStructuresTest.originalPoolsAreSound` and `datagen.render` both refuse a DT pool element
that names a `big_lost_city:` template. Keep it that way.

## Files

```
build-templates.py       writes data/dungeontrain/structure/lost_city/<name>.nbt + manifest.json  (--check)
generate-variants.py     writes processor_list/, template_pool/, structure/ JSON + structure_set entries (--check)
test_lostcity.py         unit tests (stdlib)
lostcity/archetypes/     one module per building — the designs
lostcity/variants.py     the per-building looks rolled at placement
lostcity/{blocks,canvas,shapes,floors,pad,weather,nbt_out,check,datagen,spec}.py
```

Run both scripts after editing a recipe; CI runs both with `--check` and the unit tests, and the
Java `LostCityTemplatesTest` re-reads the committed files.

## Conventions the processors rely on

- **y = 0 is the pad.** Natural-ground blocks there (`LostCityGroundProcessor.BASE`, mirrored by
  `blocks.PAD_NATURAL`) yield to the world's terrain; paving (never in that list) stays and gets
  footing. Keep a paved apron ≥ 3 blocks round the building so bite rubble has a floor.
- **Mass is full cubes only.** Panes, bars, slabs, stairs and walls are invisible to the stretch band
  search, so a curtain wall needs full glass or a pier every bay.
- **Repeats must be exact.** A tower's floors repeat every `floor_period` layers and its facade every
  `bay_period` blocks (`floors.tower()` guarantees both); the manifest records the periods and the
  variant recipes take theirs from it.
- **Air only inside the envelope.** Rooms get explicit air; the pad margin and the sky do not, so the
  biome's plants stand on the plaza and hills lean in.
- **Keep the declared `margin` clear** and `size.y` tight to the roof; `check.validate` fails otherwise.
- Footprint + widest stretch ≤ 128 (chunk-reference radius); prefer ≤ 80 wide.

## Retuning

Edit the archetype module (dimensions, palette, detailing) or `variants.py` (looks and weights), then

```bash
python3 scripts/lost-city/build-templates.py && python3 scripts/lost-city/generate-variants.py
python3 scripts/lost-city/test_lostcity.py
```

A template can also be loaded in-game with a structure block (`dungeontrain:lost_city/<name>`),
edited, and saved back over the `.nbt`. If you keep such a hand-edited file, remove that archetype from
`build-templates.py`'s output (or the `--check` step will flag the drift) and note it here.
