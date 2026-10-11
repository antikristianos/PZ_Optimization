# Tree shadows under canopies and low roofs (2026-10-11)

Question (maintainer): on canopies and low roofs, is a tree's shadow drawn under the roof, and does the game mark the
squares under them `isOutside() == false`?

Answer: the game marks the ground under gas-station canopies and pitched carports as roofed (not outdoors), and our
baked sun term (sun march, tree-crown shadows, tree silhouettes, far field) never runs on it. One term does reach under
those roofs: the tree-crown **sky** occlusion (`aoTreeCanopyPct`, part of ambient occlusion), which has no outdoors test.
Video: `docs/media/tree-sky-ao-under-canopy.mp4` (poster `.jpg`). Not fixed yet.

## How the game decides "outdoors"

`IsoGridSquare.RecalcProperties`: the `exterior` flag (what `isOutside()` returns) is set only when the square has no
room and no `haveRoof`. `haveRoof` is set when any square above it (level >= 1) holds a tile with `solidfloor` or
`BlockRain` (`IsoChunk`'s load loop, `IsoCell.checkHaveRoof` after a tile is added / removed). Rooms come from the lot
header's RoomDef rectangles (the per-square int in the lotpack is read and dropped); `emptyoutside` rooms (carports,
porches) do not count as rooms (`IsoMetaChunk.getRoomAt` skips them).

## What the map holds (offline census)

`harness/roofs/census.py` reads every lot header (rooms, emptyoutside) and lotpack of `Muldraugh, KY` with the `.tiles`
properties (the noiseworks `.patch.tiles` adds to a tile's properties; replacing them dropped `solidfloor` from every
floor in a first pass). Squares on level >= 0 with a floor and a roof / floor tile one or two levels up:

| class | squares |
|---|---|
| not outdoors (room or `haveRoof`) | 2,301,076 |
| outdoors, beside a room (the strip outside a building's S / E wall under the eave) | 59,251 |
| outdoors, beside no room (the edge row of roofs over non-room structures) | 20,282 |
| outdoors, in an `emptyoutside` room | 539 |

- **Gas-station canopies** are floor tiles one or two levels up (`roofs_04_55`, `location_shop_fossoil_01_38/39`,
  `floors_interior_tilesandwood_*`), all `solidfloor`: 77 of 101 pump spots sit under one, all `haveRoof`; the other 24
  have no canopy.
- **Pitched carports** are roofed too: at the carport of the 2026-09-21 roof-flicker video (11021-11024 / 6718-6721) the
  4 x 4 under the rain-blocking `roofs_30_03_*` slope is `haveRoof`; only the row under its eave trim (6722) is outdoors.
- **Eaves**: the outdoor-under-a-roof squares are the one-square strips just past a building's south / east wall, under
  the eave trim (`roofs_accents_*`, `WestRoofB`) or the roofs' edge pieces (`roofs_0x_6/7`), none of which block rain.
  The game rains there too. Roof sprites (not movables): 1,846 of 2,104 `WestRoofT` block rain, 16 of 24 `WestRoofM`, 8 of
  70 `WestRoofB`; none of the 84 sprites named "Canopy" (store awnings) does.
- **Store awnings** (`walls_decoration_01` canopies, the pie / crepe restaurants') hang on the square's own level, so the
  ground under them is plain outdoors.
- Map quirks: a few hundred map trees stand on roofed squares (e.g. 8010,11591 under a pitched roof; 12770-12777 /
  1254-1269 under a level-2 floor).

## Our shading under a roof

- **Baked sun term** (`ChunkAo` kernel): `exteriorKind` reads the exterior mask (`IsoGridSquare.isOutside` per square,
  `exteriorMask`); the sun term runs only where it says outdoors (`sunPar.w` is always 1, so the test is always on). No
  sun or tree shadow is baked under canopies or carports; eave strips get them, as the game's rain does.
- **Character sun factor** (`SunShadow.characterFactor`) and the capsule casters also test `isOutside`.
- **Tree-crown sky occlusion** (`aoTreeCanopyPct`, default 25 % optical depth per square of crown; the "dark oval under a
  tree"): `crownPath(P, up)` straight up from the texel through every crown proxy in reach (radius up to 3.3 squares for
  a tree 4.5+ levels tall), gated only by `ambientOcclusion`, **not** by the exterior mask. A crown beside or over a roof
  darkens the ground under it, though the roof already hides that sky. With `sunShadows` on it is scaled by
  `canopyK = clamp(1 - 2 x sun strength, 0.1, 1)`: full when overcast, at dusk and at night, a tenth in full sun; with sun
  shadows off it is full at noon.

## The repro

Scene: a 5 x 5 test gas-station canopy (`roofs_04_55`, level 2, 8004-8008 / 11186-11190) beside a 4-level tree at
8003,11188 on the Rosewood main road, built with the new `place_tile=floor:<sprite>@x,y,z` harness flag (the game's own
`addFloor` on a square it creates; the console line confirms `ground below outside=false haveRoof=true`). The player walks
west along the road (`--flag start=8010,11193 --flag route=W:6 --flag speed=0.3 --route-seconds 20 --flag zoom=1`), clear
noon (`time_of_day=12 weather=clear`), empty options file, `ambientOcclusion=true sunShadows=false cloudShadows=false
hdr=false`, `devCapture=4,22,30,50,ram`; four runs `skyao-cl-25`, `skyao-cl-0` (`aoTreeCanopyPct=0`), `skyao-cl-25-ao`,
`skyao-cl-0-ao` (`devAoView=1`). `harness/stitch-tree-sky.py` (queue media job) stitches them 2 x 2, panels following the
canopy footprint (outlined; camera offset +12 / +50 capture px measured on the canopy roof and the trunk).

Inside the canopy footprint, default minus `aoTreeCanopyPct=0`: AO term alone 16.5/255 darker on average (max 110),
picture 4.2/255 (max 36); about half of the tree's oval falls under the canopy. In the overcast takes (`cloud=1`, sun
shadows on) the same oval was 30/255 in the AO view, but the stock screen-space cloud overlay drifted differently in each
run and swamped the picture, so they were not used.

Dead ends on the way: the railway conveyor at 1909-1911 / 10704-10725 (roofed by a level 2-3 deck, map tree beside it) —
the deck does not render and the tree is a sapling; the start square 1913,10727 was inside a factory room.

The dark half of the test canopy's roof is the placement's own artifact (the created level-2 squares carry no light), the
same in every panel.

## Fix (proposed, not done)

Skip the crown sky term on texels that are not outdoors, keeping it on the tree's own trunk and lower crown: in the AO
kernel's sky-term line, `(tree || exteriorAt(...))` beside the existing gates (the exterior mask is uploaded with the sun
term; it would need to be filled whenever `aoTreeCanopyPct > 0`). Re-run the scene above for the before / after.

## Tools

- `harness/roofs/census.py <out.pkl>`: the census above (whole map, ~60 s on 14 processes); `covered.py` the tile
  properties and a first pass without rooms; `roofmap.py X Y R` a square map round a spot (room / emptyoutside / haveRoof /
  exterior, the level-1 tile); `levels.py X0 X1 Y0 Y1` the tiles above level 0 in a box; `area.py find TILE...` squares
  holding tiles.
- Harness: `place_tile=floor:<sprite>@x,y,z` (new, see above); the `walk=probe` map prints trees as digits, their height
  in levels (`ChunkAo.treeLevels`, the crown proxy's size).
