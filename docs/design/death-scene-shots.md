# Death scene: shot table

Branch `dev/train-stays-after-death`, version 0.1561.5. Offsets are from the subject as (along the
train, up, across); along is positive ahead. Carriages are 7 wide.

The body is a Sable: Ragdolls ragdoll wearing your skin and gear, left where you died (server
`ship/sable/DeathBodyRagdoll`, sent as `bodyId` on the death scene packet). Shots whose subject is
"the body" or "you" follow that ragdoll wherever physics takes it; your own player model is hidden
while it exists. Without the ragdoll (void death, spawn refused) they frame the pinned player
position as before.

## Phase 1 (plays once)

| # | Label | Camera | Subject | Position | Hold | Skipped when |
|---|---|---|---|---|---|---|
| 0 | POV | own eyes through the carriage render pose, red wash, death tilt | you | eyes | 0.75 s | never |
| 1 | spot | orbit riding the carriage | the block that killed you (fire, campfire, magma, lava, cactus, berry bush, powder snow, water, dripstone, the wall you suffocated in), else the body where it died | radius 6, height 3; falls back to 4 / 2.5 / 1.6 and lower; lens in open air | 3 s | void death; or no killing block and no killer shot follows while the carriage shot plays (a blocked arc falls back to tighter radii, other lens heights, and finally a lens just over the head) |
| 2 | killer | orbit following the entity | the killer | radius 5, height 2.5 | 3 s | no killer, gone, no clear arc |
| 2.5 | mob | orbit following the entity | a creature you fought nearby, a friend (gifts exchanged), else a nearby pet of yours | radius 5, height 2.5 | 3 s | none within 32 blocks, no clear arc |
| 3 | carriage | zoom out 3 s, blend 1 s, world-locked hold tracking you | you | starts 1.6 outside the carriage wall at eye height on the window side, looking in; ends (-5, 6, wall+9) | 6 s | died off the train (3 s tracking shot from (-3, 2.5, 8) instead) |
| 3b | body-out | zoom out 3 s, blend 1 s, world-locked hold tracking you | you | pulls out from beside the body whichever way is clear, tried in order: straight up (0.5, 2, 0) to (1.5, 12, 0), either side (0, 1.5, +-4) to (-4, 6, +-14), behind (-4, 1.5, 0) to (-14, 5, 0), ahead (4, 1.5, 0) to (14, 5, 0) | 6 s | the spot shot or the carriage/leaving shot played. Never skipped otherwise: with no clear pull it takes a close-up, the longest short pull (2.5, 1.8 or 1.2 blocks) with the body in view, up first, then the up-diagonals, then level all round; and failing even that it holds just over the head, unchecked |

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
| T4 | window-out | train-locked | the passing wall | from the far side of the carriage: just inside the opposite wall at eye height, looking across the interior and out through the window | 5 s | fewer than 5 other loop shots played this pass; the view across to the window is clear |
| L1 | feature | world-locked, aim tracks it | a contents template, else a carriage group, within the 3 nearest groups | beside it at (0, 4, +-14) for contents, (0, 9, +-26) for a group | 4 s | server listed one and a side is clear |
| L2 | mob-arc | orbit following the entity | a random creature riding the train within 64 blocks, redrawn each pass | radius 3, height 2 | 5 s | one exists and an arc is clear |
| last | eyes-forward | train-locked | the train ahead | your eyes, looking forward | 5 s | nothing else at all played this pass |

## Every camera shot

Lens path sampled before the shot (9 points on moves, the chosen arc on orbits), every sample in
open air and the subject in view; terrain in the way skips the shot rather than pulling the lens
closer; the lens is checked every tick and the shot is cut the moment it is inside a block; fade
through black 8 ticks each side of a cut; HUD, hand and death tilt off. Left and Right step through
the list, Space continues. In dev builds the shot label is drawn top-left.
