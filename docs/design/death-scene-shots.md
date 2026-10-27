# Death scene: shot table

Branch `dev/train-stays-after-death`, version 0.1560.9. Offsets are relative to the body: **along**
the train (+ is ahead, the direction of travel), **up**, **across** (+ is one side of the track,
- the other). Carriages are 7 wide, so anything within 3.5 across is inside the train.

## Sequence

| # | Label | Kind | Subject | Offset (along, up, across) | Hold | Skipped when | Description |
|---|---|---|---|---|---|---|---|
| 0 | POV | POV | body | own eyes | 40 t (2 s) | never | The player's view, red wash rising, vanilla death tilt. The only shot with the wash. |
| 1 | spot | ORBIT | death spot (world-locked) | radius 6, height 3; falls back to 4 / 2.5 / 1.6 and lower lens | 80 t (4 s) | void death, or no clear angle at any radius | Circles where you died, aimed at that point. Sweeps the longest clear arc, up to half a turn. |
| 2 | killer | ORBIT | killer entity (live) | radius 5, height 2.5; same fallbacks | 80 t | no killer entity, or it is gone/dead on the client, or no clear angle | Circles whoever killed you. Re-picks the arc if they walk behind cover. |
| 2.5 | mob | ORBIT | last other mob fought (live) | radius 5, height 2.5; same fallbacks | 80 t | nothing fought recently within 32 blocks, or no clear angle | Circles the other mob you were dealing with. |
| 3 | carriage | DOLLY | body, carriage-locked | from (3, 2, 5) to (-6, 8, 14) | 80 t | died off the train | Eases out from beside the body to a high rear three-quarter view of the carriage. |
| 4 | leaving | TRANSITION | body, world-locked lens | (-3, 2.5, 8) | 100 t (5 s) | never | Lens stays where the shot starts, aim follows the body as the carriage rolls out of view. |
| 5 | W-5 | WORLD | track point (world-locked) | (0, 24, 14) | 120 t (6 s) | never | High overhead, a little to the side: the train slides through below. |
| 6 | W-6 | WORLD | track point | (0, 3, 26) | 120 t | never | Low and wide from the side: carriages pass left to right. |
| 7 | W-7 | WORLD | track point | (-55, 18, -28) | 120 t | never | Far behind, high, far side: the train pulls away into the distance. |
| 8 | W-8 | WORLD | track point | (45, 4, -24) | 120 t | never | Far ahead, low, far side: the train comes toward the camera and passes. |
| 9 | W-9 | WORLD | track point | (0, 36, 44) | 120 t | never | Very high and wide: the whole line in the landscape. |
| 10 | W-10 | WORLD | track point | (-26, 5, -20) | 120 t | never | Behind, low, far side: the back of the train receding. |

Shots 5 to 10 loop until Space. Left and Right step through the list (never back into the POV;
Left from the first camera shot wraps to the last train shot).

## Camera kinds

| Kind | Lens | Aim | Moves with the train |
|---|---|---|---|
| POV | player's eyes | player's look | yes |
| ORBIT | circle around the subject, clear arc only | subject | death spot: no; killer/mob: follows the entity |
| DOLLY | eased from one carriage-space offset to another | body | yes (re-projected through the carriage render pose each frame) |
| TRANSITION | fixed in the world where the shot starts | body (live) | no (aim tracks the body) |
| WORLD | fixed in the world where the shot starts | where the body was at the shot's start | no |

## Every cut

Fade to black: 8 ticks out, 8 ticks in. HUD, first-person hand and death tilt are off on every
camera shot. Each lens is pulled toward the body to clear terrain and carriage blocks once when
its shot starts. In dev builds the shot label is drawn top-left.
