# Death scene: shot table

Branch `dev/train-stays-after-death`, version 0.1560.20. Offsets are from the subject as (along the
train, up, across); along is positive ahead. Carriages are 7 wide.

## Phase 1 (plays once)

| # | Label | Camera | Subject | Position | Hold | Skipped when |
|---|---|---|---|---|---|---|
| 0 | POV | own eyes through the carriage render pose, red wash, death tilt | you | eyes | 2 s | never |
| 1 | spot | orbit riding the carriage | the body where it died | radius 6, height 3; falls back to 4 / 2.5 / 1.6 and lower; lens in open air | 3 s | void death only (a blocked arc falls back to tighter radii, lower or higher lenses, and finally a lens just over the head) |
| 2 | killer | orbit following the entity | the killer | radius 5, height 2.5 | 3 s | no killer, gone, no clear arc |
| 2.5 | mob | orbit following the entity | a creature you fought nearby, a friend (gifts exchanged), else a nearby pet of yours | radius 5, height 2.5 | 3 s | none within 32 blocks, no clear arc |
| 3 | carriage | zoom out 3 s, blend 1 s, world-locked hold tracking you | you | starts 1.6 outside the carriage wall at eye height on the window side, looking in; ends (-5, 6, wall+9) | 6 s | died off the train (3 s tracking shot from (-3, 2.5, 8) instead) |

## Phase 2 (loops until Space)

| # | Label | Camera | Subject | Position / move | Hold | Plays when |
|---|---|---|---|---|---|---|
| 5 | W-5 | arc, world-locked | death spot | (0, 24, 14), 50 degree arc | 6 s | path clear |
| 6 | W-6 | dolly | death spot | (-14, 3, 26) to (14, 3, 26) | 6 s | path clear |
| 7 | W-7 | dolly | death spot | (-55, 18, -28) to (-42, 15, -22) | 6 s | path clear |
| 8 | W-8 | arc | death spot | (45, 4, -24), -25 degree arc | 6 s | path clear |
| 9 | W-9 | arc | death spot | (0, 36, 44), 35 degree arc | 6 s | path clear |
| 10 | W-10 | dolly | death spot | (-26, 5, -20) to (-18, 4, -15) | 6 s | path clear |
| T1 | side-rise | train-locked rise | the train's side | beside the carriage 1.2 off the wall, from 0.4 above the deck up to just over the roof, looking along the train | 6 s | no open-air shot played this pass; path clear |
| T2 | above-back | train-locked drift | the tracks ahead | 2 above the roof, from 2 ahead of you to 8 behind, looking forward | 6 s | same, and a roof |
| T3 | roof-skim | train-locked | the train ahead | 1.2 above the roof over you, looking forward and down | 5 s | same, and a roof |
| T4 | window-out | train-locked | the passing wall | just inside the carriage wall at eye height, window side, looking out | 5 s | fewer than 5 other loop shots played this pass |
| L1 | feature | world-locked, aim tracks it | a contents template, else a carriage group, within the 3 nearest groups | beside it at (0, 4, +-14) for contents, (0, 9, +-26) for a group | 4 s | server listed one and a side is clear |
| L2 | mob-arc | orbit following the entity | a random creature riding the train within 64 blocks, redrawn each pass | radius 3, height 2 | 5 s | one exists and an arc is clear |
| last | eyes-forward | train-locked | the train ahead | your eyes, looking forward | 5 s | nothing else at all played this pass |

## Every camera shot

Lens path sampled before the shot (9 points on moves, the chosen arc on orbits), every sample in
open air and the subject in view; terrain in the way skips the shot rather than pulling the lens
closer; the lens is checked every tick and the shot is cut the moment it is inside a block; fade
through black 8 ticks each side of a cut; HUD, hand and death tilt off. Left and Right step through
the list, Space continues. In dev builds the shot label is drawn top-left.
