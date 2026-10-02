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
build-templates.py       writes data/dungeontrain/structure/lost_city/<name>.nbt, <name>.variants.json + manifest.json  (--check)
generate-variants.py     writes processor_list/, template_pool/, structure/ JSON + structure_set entries (--check)
test_lostcity.py         unit tests (stdlib)
authored-variants.json   buildings whose variants document is hand-kept
lostcity/archetypes/     one module per building — the designs
lostcity/variants.py     the per-building looks rolled at placement
lostcity/variantdoc.py   the per-building block-variant pools rolled at placement
lostcity/{blocks,canvas,shapes,floors,pad,weather,nbt_out,check,datagen,spec}.py
```

Run both scripts after editing a recipe, `build-templates.py` first — `generate-variants.py` reads which
buildings have a variants document from disk. CI runs both with `--check` and the unit tests, and the
Java `LostCityTemplatesTest` re-reads the committed files.

## Block variants

A cell of a building can hold a pool instead of one block. `<name>.variants.json`, beside the template,
is a DT variants document — the schema the editor's block-variant menu writes for carriages and tunnels —
and the `dungeontrain:lost_city_variants` processor rolls it per placement
(`worldgen/LostCityVariantsProcessor`, documents loaded by `worldgen/LostCityVariantDocs`). The setting
`lostCityBlockVariants` (common config, default on) turns the roll off; a building with no document is
never affected.

- **Seeded by the generator.** `variantdoc.RULES` names a block and what may stand in its place; only
  small self-supporting details vary (props, plants, lamps, vine ends), up to `variantdoc.CAP` cells a
  building, shared out across the rules.
- **Kept by hand.** List the building in `authored-variants.json`; the generator then leaves its document
  alone and only validates it. Cells are `"x,y,z"` in template coordinates, above the pad, on a cell the
  template has. Stage placeholders, mob entries and connect modes are refused — nothing at worldgen can
  resolve them.
- **In a processor list** the processor appears twice: `"phase": "mark"` first, `"phase": "roll"` after the
  stretches and swaps and before bite, facade and truncate. A floor or bay a stretch adds rolls on its own;
  cells in a lock group pick together across the whole building. A pick goes through the design's swaps, so
  a recolour and the dry look cover it. Growth entries (`"growth": "down 1-4"`) hang their column into free
  space of the piece.
- **Not applied:** the `containers` role and loot-prefab links (an entry's own `nbt` is carried).

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

The easy way to hand-edit a building is the in-game **Buildings** editor (`/dt editor buildings`). In
dev mode, saving a shipped building writes its `.nbt` here and adds its name to `authored.json`:
`build-templates.py` then leaves that file alone (no rewrite, no `--check` drift) and takes the
manifest's `size` from it. Keep the conventions above by hand — a broken floor repeat just means the
stretch designs place that building unstretched. Delete the name from `authored.json` to hand the
building back to its recipe.

Players use the same editor: their edits load in place of the shipped building in their own worlds,
and new buildings join the roster through the `player_building` slot (placed as built, no processors).

A template can also be loaded in-game with a structure block (`dungeontrain:lost_city/<name>`),
edited, and saved back over the `.nbt` — list it in `authored.json` the same way.
