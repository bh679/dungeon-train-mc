# Train wind tuner

Wind blows on the train once it leaves the first Upside-down band. Its shape lives in
`src/main/resources/data/dungeontrain/wind/profile.json`: a list of **stages** keyed by blocks past the
Upside-down end, blended linearly. The last stage holds forever.

```
python3 scripts/wind/serve.py
```

opens `http://127.0.0.1:8796`, which shows:

- **Along the line**: steady push, flutter range, gust peak, gusts/min and direction wander against
  distance, with the band strip from `bands.json` on top. Click to pick a spot.
- **What it feels like**: the actual wind at that spot over a time window (the same code the game runs),
  so you can compare constant wind with bursts. Tick **ride the train** to let the spot move at train
  speed. The heading chart below shows the slow swings.

**Save** writes `profile.json`. In a dev client, `/dungeontrain debug wind reload` applies it live.

| File | What |
|---|---|
| `web/wind.js` | Line-for-line port of `wind/WindField.java`. |
| `golden.json` | Vectors written by `WindFieldTest` (`UPDATE_WIND_GOLDEN=1 ./gradlew test --tests '*WindFieldTest*'`). The page badge goes red if the JS and Java drift. |
| `bands.json` | Band edges relative to the Upside-down end, written by `/dungeontrain debug wind export` (copy it from `run/wind-bands.json`). |

Units: push is blocks/tick² at multiplier 1. A grounded player settles at about 2.2× that in blocks/tick
(walking is about 0.22), airborne about 11×.
