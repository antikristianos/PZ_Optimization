# Reflective props (2026-10-08, `mirrorsProps`, pzopt.Props)

Maintainer's request: "add reflections to all those props [the ones that are not counted as windows or mirrors but can
reflect, like glass tables]; secondary objective: make them virtually nothing; use all state-of-the-art techniques used in
other games, don't discard any idea, implement them one by one and profile them". Worktree `~/pzopt-wt/props`, branch
`prop-reflections` (from origin/master 84b483cc).

## Result (flip, Mesa radeonsi, 1920x1080, 60 fps cap; defaults)

| Scene | whole-frame GPU vs off (median) | per pass |
|---|---|---|
| showroom, 9 props, zoom 0.5, still (`prop-v12b`) | +107 us | composite 96, models 334 on 1 frame in 3, static 250 on 1 frame in ~110 |
| walking past the real kitchen / store, zoom 0.75 (`prop-walk1`) | +129 us | composite 113, static 188 on 1 frame in 30 |
| walking past real store fronts, mirrors on, props on vs off (`prop-final-true` / `-false`) | +115 us a frame (composite +93, models +13, static +10) | frame p99 17.5 / 17.5 ms, p99.9 22.8 / 22.8, GPU load 19 / 18 %, game thread 27 / 27 % of a core, render thread 22 / 21 % |

At the flip's 60 fps cap that is 0.7 % of a frame with nothing else changed (the game and render threads unchanged); the
first build cost +247 us and 289 us of render thread per composite. Not measured yet on the desktop (4090) or Windows;
the Mac never started a game this session (the released build neither: an environment problem there).

## The props

`harness/props/catalog.py` reads the tile definitions and keeps every sprite that is neither a window (`WindowN/W`,
`GlassRemovedOffset`, ...) nor an `IsMirror` tile but has glass, a screen, polished metal or glazed ceramic:

| Class | Sprites | Examples |
|---|---|---|
| glass, translucent texels | 290 | glass doors (`fixtures_doors_01/02`, the sliding glass doors' closed leaves, the restaurant / shop brand doors, garage doors), store-front panes that are not windows (`walls_commercial_*`, Spiffo's), mall and escalator railings, shower screens, display counters and cases (shop, pie, pizza, Seahorse, theatre, cantina), the glass table, the china cabinet, the pinball glass |
| glass, opaque | 17 | glass-door fridges, the vending machines' fronts |
| screen | 22 | televisions, the security monitors, the computers, arcade cabinets, the jukebox |
| mirror | 6 | the gym's wall mirrors (`recreational_sports_01_74-79`: no `IsMirror` property, so the mirrors pass skipped them) |
| steel | 67 | steel counters, industrial / chrome sinks, stoves and ovens, microwaves, toasters, steel fridges, the 50s diner tables |
| ceramic | 38 | toilets, white and beige sinks |

Left out: the chandeliers (no depth map), the tiled bathroom walls (every bathroom wall would go per frame), neon signs.

## Geometry: every texel's own plane from the game's depth maps

A prop is not one pane: a glass counter has a glass top and two glass faces, a steel counter a top and two sides, a TV its
screen. `harness/props/masks.py` reads each sprite's depth map (`media/depthmaps/DEPTH_<tileset>.png`, else the tile
`tileDepthTextureAssignments.txt` assigns; 461 of 465 resolve). In the 2x frame a texel (X, Y) lies at u = x - y =
(X - 64) / 64, v = x + y - 6z = (Y - 192) / 32 from its square's north floor corner, with iso depth w = x + y + 2z =
4 (1 - d). A least-squares plane w = a u + b v + c over the best of five 4 x 4 windows round the texel (a texel on a fold
keeps its own face) names its face, the only three the iso camera sees:

| Face | plane | (dw/du, dw/dv) | reflected ray per square (world) | on screen |
|---|---|---|---|---|
| top | z = c | (0, 1) | (-1, -1, +1/3) | straight up the column (v -4), iso depth -4/3, climbs 1/3 level |
| south | y = c | (4/3, -1/3) | (-1, +1, -1/3) | down-left (u -2, v +2), iso depth -2/3, falls 1/3 level |
| east | x = c | (-4/3, -1/3) | (+1, -1, -1/3) | down-right |

Texels whose best fit is none of them (curved glass, a toilet bowl) are left out. The plane offset is the median of the
face's texels nearby (the depth map is 8-bit: 0.016 squares a step). Atlas: RG8, 64 x 128 per sprite, R = face << 6 |
offset ((q / 32 - 0.25) squares / levels), G = reflectance (glass: every face texel, the sprite's translucent texels decide
at run time; opaque glass: light grey-blue; screens: the largest dark blob on a vertical face; steel: low-saturation grey;
ceramic: white). `props.txt` adds the class, the mode and the main vertical face (for the mirrored people).

## Design

The props are a third kind of reflector in pzopt.Mirrors' pipeline (findings-mirrors-2026-10-03.md):
1. Drawn per frame (`Mirrors.perFrame`: most are already, `glassTilesPerFrame`), captured as they draw (their quad, the
   atlas cell, the flip: a flipped sprite's south and east faces swap).
2. Static pass (every 4th frame, the atlas tile kept while the scene holds): every reflective texel's pane point from its
   face's plane; a top marches up its column (no floor shortcut: the ray never comes down), a south / east face as a pane
   of that orientation (the mirrors' march: floor first, then taps and bisection). Glass marched at half resolution.
3. Composite: the props in a second instanced draw after the panes; no analytic plane for the hardware depth test (a prop
   has several faces), so each texel tests its own surface depth against the frame's depth (read as a texture, depth writes
   off): glass writes no depth, whoever stands behind it passes, whoever stands in front hides it.
4. The mirrored characters / vehicles in the prop's main vertical face (doors, store fronts, screens, fridges).

## Techniques measured

Machine: the flip (Ayaneo Flip, AMD APU, Mesa 26.2 radeonsi, 1920x1080, 60 fps cap; the GL 4.3 path, untested there before).
Scene: the outdoor showroom (9 props in a row 3 squares north of the player, the sheriff car beside him), zoom 0.5, still camera;
same-run cycles of `devMirrorsAlternate=1000` (`harness/mirrors/cost.py`: whole-frame GPU per period, per-pass GPU timers).

| Step | Run | Result |
|---|---|---|
| first build: whole frame GPU vs mirrors off | `prop-v2` | +247 us median; composite 238 us, model pass 646 us on frames it draws, static pass 386 us on 1 frame in ~35 |
| render thread: the data rows uploaded from client memory | `prop-v1` | **289 us a composite** in `glTexSubImage2D`: Mesa's threaded GL waits for its driver thread on a client-memory upload |
| data rows through a persistently mapped unpack buffer (`mirrorsPboUpload`) | `prop-v2` | upload 289 -> **5 us** |
| composite: per-pane rows fetched once per vertex, flat to the fragments (`mirrorsFlatRows`) | `prop-v3` vs `-noflat` | 234 -> 227 us: not the cost |
| composite split (dev bit 4194304, no prop composite) | `prop-v3` | panes 9.5 us, **props 218 us** |
| props' occlusion through `gl_FragDepth` + the hardware depth test instead of reading the frame's depth as a texture (`mirrorsPropDepthWrite`) | `prop-v4` | props 218 -> **110 us** (reading the depth buffer makes AMD decompress it); whole frame +230 -> **+123 us** |
| mirrored models: one draw per orientation into a scratch impostor, the whole box copied per plane with its depth texture (`mirrorsModelShare` v1) | `prop-v5` | model pass 1218 -> **2012 us** (worse: box-sized copies x 5 planes + another depth decompress) |
| glossy 8-tap blur in the composite (dev bit 33554432 off) | `prop-v5` | props composite 124 -> 91 us without it: the blur is ~33 us |
| model share v2: one copy per (model, plane) clipped to the plane's panes, at the model centre's depth (no depth texture, early-Z kept; the scratch depth a renderbuffer) | `prop-v6` | model pass 1223 -> **978 us** per drawn frame |
| prop quads cut to their reflective texels' box (`mirrorsPropTightQuads`, the box from masks.py) | `prop-v6` | static pass 316 -> 257 us, composite 123 -> 110 us |
| mirrored models at half resolution when no silvered mirror is on screen (`mirrorsGlassModelScalePct` 50) | `prop-v7` | model pass 976 -> **322 us** per drawn frame |
| static pass without its draws (dev bit 512) | `prop-v7` | 379 -> 8 us: the static pass is the march itself (10 props new together every ~31 frames), not a fixed cost on Mesa |
| props re-marched every 240 frames (`mirrorsPropStaticReuse`; a prop's reflection changes only when the world does, a pan never invalidates a tile) + the pass's marched area capped (`mirrorsStaticBudgetPct` 3 % of the viewport) | `prop-v8` | static passes 1 in ~31 -> 1 in ~110 frames, 250 us each; whole frame **+108 us** median |
| glossy as a pre-convolved mip chain of the atlas, one `textureLod` tap (`mirrorsPropGloss=mip`) | `prop-v8-mip` | composite 100 -> 96 us, but the mip build adds ~110 us to each glossy static pass: kept as an option, blur stays |
| glossy baked at the static pass: 4 rays jittered round the mirror direction by the roughness, averaged (`mirrorsPropGloss=bake`, general-direction march `marchDir`) | `prop-v9-bake` | composite 100 -> 96 us, static 250 -> 327 us: true glossy, kept as an option |
| composite split, constant colour (dev bit 8) | `prop-v10-z0.5` / `-z1` | props 95 us at zoom 0.5 (53 fixed per-pixel: raster, the mask read, the depth export, the blend; 42 shading), 40 us at zoom 1 (26 / 14): fill, scales with the px |
| props smaller than `mirrorsPropMinPx` (600 px) on screen skipped (zoomed out) | | no march, no composite for props a few px wide |
| conservative depth: the props' quads at their square's nearest depth, `layout(depth_greater) out float gl_FragDepth` (`mirrorsPropConservativeDepth`) | `prop-v12b` vs `-v11` | composite 99 -> 96 us (noise; it pays where something stands in front of a prop). The first try declared it before an `#extension` line: NVIDIA linked, **Mesa refused, the flip ran with mirrors off** (`prop-v12`); MirrorsShaderTest now also links every program with Mesa llvmpipe |
| Hi-Z: the nearest scene per 16 x 16 px block built at each static pass, the march jumps over blocks the scene stands behind (`mirrorsHiZ`, off) | `prop-hiz-false` / `-true2` | static pass 370 -> **802 us**: the build (256 depth reads a block over the screen) and the extra fetch per tap cost more than the 4-32-tap rays save. The first build looped forever on the GPU (`ceil` of a float index stepped back; the flip's game hung at frame 211) |
| glossy in the real kitchen (steel counters, sink; zoom 0.5, props re-marched every 30 frames for samples): blur vs bake + history (each refresh turns the jitter and blends into the tile at 34 %: the tiles do not move with the camera, so the history needs no reprojection; `mirrorsPropGlossAccumPct`) | `prop-gloss-blur` / `-bake` | composite 236 -> **189 us**, static 309 -> 326 us a pass; at the default 240-frame refresh bake is ~40 us a frame cheaper and looks the same or better: **bake is the default** |
| walking past the real kitchen / store (`find=props` cluster 1, route W:8, zoom 0.75) | `prop-walk1` | whole frame +129 us median; composite 113 us; static pass 188 us on 1 frame in 30 (props coming on screen); 10,718 hidden pane-frames skipped |

### Look

| Change | Why |
|---|---|
| steel tinted (x 0.55, F0 of steel), roughness 60 %, strength 25 %, blur radius from (hit distance + 1 square) | the kitchen's steel counters read as see-through (the floor tiles continued in their fronts, the striped wall sharp in their top) |
| a prop's reflection times its own light (its vertex colour; since the rebase onto 75754f83 the mirrors' own `paneLight`, which also hides panes in rooms the player never saw) | a counter out of the player's sight, drawn near black, took a light grey reflection |
| opaque glass on vertical faces only; translucent glass only where it is light (shadows are dark); nothing at floor height is a top | the fridge's grey cap reflected; a glass table's soft drop shadow reflected the sky as blue notches on the floor |
| the gym's mirrors treated as mirror tiles (`mirrorsPropMirrorAsMirror`): the room rebuilt behind the glass, people placed as in a mirror | they are silvered mirrors without the `IsMirror` property |
| an outdoor top's misses show the sky (the skybox gradient at its fixed mirror direction, north-west 30 degrees up) plus a GGX sun lobe (`mirrorsPropSky`) | glass tables outdoors reflected nothing; a flat top of the iso camera can only ever catch a low north-western sun |

### Steel, ceramic and screens: people only (2026-10-09, maintainer: "steel surfaces still look translucent")

Every way of showing the static scene on an opaque prop read as see-through: mirrored by a vertical face in the iso view, the
floor continues the floor beside the prop, and a top's mirrored wall continues the wall above it. Tried on the desktop in the
real kitchen and the showroom (runs `metal-*` .. `metal6-*`):

| Composite | Look |
|---|---|
| the reflection blended over the steel (alpha), tinted x 0.55 | translucent: the floor tiles in the cabinet fronts, the striped wall in the top |
| the reflection's brightness as a modulation of the steel's own colour (blend `GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA`) | the steel keeps its look, but the bright kitchen lifts the whole face: a lighter, see-through-looking sheet |
| the same with the baked jittered rays at 90 % roughness | sparkling grain |
| the modulation by the variation round a 16 px mean (zero mean) | the asphalt's texture on the counter's face: see-through again |
| **only the mirrored people / cars, as their brightness against the static reflection under them** (`mirrorsPropMetalBlend`) | solid, as stock; whoever walks by shows as a soft smudge |

A dark TV screen showed the asphalt the same way: screens joined the group. The glass keeps the whole reflection (it is
see-through). Found in the same runs: the opaque props drawn per frame wrote no depth (the translucent pass), so a glass
table's reflection behind the TV was drawn over the TV; they now write their depth as their baked selves did
(`mirrorsPropDepth`, override-edits.md). The glossy default became `mip` (the steel's own blur, rarely used now).

### People mirrored in the tops (`mirrorsPropTopModels`)

A top z = c is a plane of the model pass too (the camera's reflection in the model frame: y' = 2 yc - y). Two findings:
- the character model's frame stands 0.72 units off its feet (its -0.48 x 1.5 offset): the first images came out 0.31 levels
  too high (layer read back with `devMirrorsLog`: 699..942 px against 463..704 predicted; with the offset 473..717).
  `mirrorsTopModelYPct` 72 (vehicles 0, not yet checked).
- a prop's top and its front face stand side by side on screen, so a model's images in both overlapped in the one layer: the
  tops have a layer of their own (two passes of the model flush).
- the iso geometry limits it: a top's ray goes straight back and climbs a third of a level a square, so a standing person shows
  only in the strip of a top within ~0.8 squares in front of them (`prop-top15`: the legs in the table top behind them).
- dev views 7 / 8 (the raw layers over the reflector quads) must write `gl_FragDepth` on their early return in the props'
  program, or nothing draws (the first probes looked empty for that reason).

## Sliding glass door leaves (2026-10-09 open, fixed 2026-10-10)

Found making the Workshop card (`harness/props-reflect-card.py`, the Rosewood back yard, 7951,11503-04 and 11507-08): only half
of each sliding glass door reflected. A sliding door is two tiles:

| Part | Sprites | Tile data |
|---|---|---|
| fixed pane | `fixtures_doors_01_104-107` (brown frame), `112-115` (white) | `WindowW` / `WindowN`, `MaterialType=Glass`: a window, the mirrors' window path |
| sliding leaf | `fixtures_doors_01_108-111` (brown), `116-119` (white) | `Material=Door`, `doorTrans`, no glass word; only `DoorSound=SlidingGlassDoor`, and that lives in `tiledefinitions_noiseworks.patch.tiles` |

Three causes, one after the other:
1. `catalog.py` read each `.tiles` file on its own, so the patch file's `DoorSound` never met the leaf's other properties. It
   now merges every file per sprite and takes `DoorSound=SlidingGlassDoor` (without a window key) as glass; `masks.py` leaves
   out the open leaves (110/111, 118/119: the bare frame, the glass slid behind the fixed pane; the white frame passed the
   opaque-glass test). 434 -> 438 props, every old atlas cell byte-identical.
2. With the leaf captured, the scene reflected in it (dev view 2: the leaf masked, static hits) but the player's image did not.
   The leaf's glass stands 0.06 in front of the wall line (its own track), so it made a plane of its own beside the windows'
   plane (`addPlane` merged within 0.05). A person before both was in two planes of one orientation, which sends them through
   `Share.draw` (`mirrorsModelShare`): drawn once mirrored in a reference plane, copied into each plane. The reference was
   `KS[0]`, the frame's first plane of that orientation, whoever stood before it: in the sunroom an N window five squares south.
   Every copy came out empty (dev view 9: neither green nor red on the N wall; `mirrorsModelShare=false`: the image back).
   The reference is now the plane most of the sharing models show in.
3. Two planes on one wall line also gave two copies of the person, 0.14 squares apart, over the leaf: planes now merge within
   0.1 of a square, so the leaf joins its wall's plane.

Run `slide-leaf-fix1` (desktop, sunroom 7938-7945,11452: white leaf 7940,11452 between fixed panes; walk
`7938.6,11452.4;7940.4,11452.4`, zoom 0.5, noon, view 9 every other second): one image of the player over the leaf and the N
panes. Cause 2 was not measured on its own (with cause 3 fixed the sunroom has one N plane; two isolation walks smashed the leaf).
Pitfalls of the rig: the Rosewood house burns down during runs (the leaf broke, the doors changed state between runs), and the
movement-key walk opens or smashes a door it brushes, so keep the walk 0.4+ off the glass and away from its ends. Closed leaves
anywhere on the map: `harness/props/findtile.py "<game>/media/maps/Muldraugh, KY" X Y <sprite>...` (the lotheader / lotpack reader, 394 squares; nearest the bench save
7940,11452 and 7938,11463).

## Rigs

- `harness/props/catalog.py --sheet`, `masks.py --sheet` (faces coloured: top yellow, south cyan, east magenta).
- Showroom: `--flag start=8147,11507 --flag place_tile=location_shop_generic_01_96@8144,11505/furniture_tables_low_01_8@8145,11505/appliances_television_01_1@8146,11505/fixtures_counters_01_33@8147,11505/recreational_sports_01_74@8148,11505/appliances_refrigeration_01_18@8149,11505/fixtures_doors_01_37@8150,11505/fixtures_bathroom_01_2@8151,11505`.
- `tests/pzopt/MirrorsShaderTest.java`: every Mirrors program (GL 4.3 and 4.1) links with the real driver headless.
- `devMirrorsPropSkip` (the measurements above used `devMirrorsSkip` bits, which the mirrors' room pass took over on the
  same day): 1 props not captured, 2 quads not cut to their reflective box, 4 data rows from client memory, 8 no prop
  composite, 16 mirrored models at full resolution, 32 no model share. Dev views 7 / 8: the raw model / top layer over every
  reflector quad. `devMirrorsTopTest` (1 no scissor, 2 no depth test) for the top layer pass; `devMirrorsLog` reads the
  layer back once (its drawn box).
- `--flag find=props [find_rank=N]`: the route starts beside the N-th densest cluster of reflective props within 80 tiles.
- `harness/stitch-props.sh`: the stock-look vs reflections-on video (`docs/media/prop-reflections-stock-vs-on.mp4`) from
  eight flip `devCapture` runs `pv-<show|kitchen|store|tables>-<false|true>`.
