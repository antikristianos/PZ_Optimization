# Puddle ripples (2026-10-10 / 11)

Objective (maintainer, 2026-10-10): puddles ripple where characters walk through them, at virtually no cost; implement
every current technique, one by one, and profile each. Worktree `~/pzopt-wt/ripples`, branch `puddle-ripples`.

Code: `pzopt.Ripples` (sources, the frame's rings, binning, the puddle shader patch), `pzopt.RipplesModel` (the water
model: iWave and its impulse response), `pzopt.RipplesWorld` (persistent world bins), `pzopt.RipplesGpu` (the compute
modes). Overrides: IsoMovingObject (fields), IsoZombie.update (the stride hook), ShaderUnit (patch chain), PuddlesShader
(uniforms), FBORenderCell (gather + GPU section) — `docs/override-edits.md`. Keys: `ripples` (Enhancements tab, off by
default like every enhancement; the shader patch is made only when it is on at launch), `ripplesMode` (default `alu`),
`ripplesStrengthPct`, `ripplesVehicles`, `ripplesPerSec` (160). Dev: `devRipplesAlternate=<ms>` + `devRipplesModes=a|b|..`
(in-run A/B: GPU section `puddles.off` / `puddles.<mode>`, phases logged in batches), `devRipplesStress=N` (N seeded
walkers round the player), `devRipplesView` (the puddles paint the ring slope), `devRipplesHookTiming`, `devRipplesBinInline`,
`devRipplesWorldStage`. Tools: `harness/ripples/cost.py <run>` (per-mode GPU / render / game thread), `gtshare.py <run>`
(share of the sampled game-thread stacks). Test: `tests/pzopt/RipplesTest.java` (model, the four shaders through
glslangValidator, world bins).

## The water

The rings are real water, not a texture. **iWave** (Tessendorf, "Interactive Water Surfaces", 2004) on 16 texels a
square at 60 Hz: `h' = a1 h - a2 h_prev - a3 (G * h)`, `G` the 13 x 13 vertical-derivative kernel
(`sum k^2 exp(-k^2) J0(k r)`), g = 0.032, alpha = 0.02 a step. It is dispersive like deep water: a footstep spreads as a
train of rings filling a growing disk, short crests inside, the long waves leading, crests born at the back of the train.

- `sim` runs exactly this on the GPU (only the 2 x 2-square tiles a footstep woke in the last 2 s, two RG16F textures
  ping-ponged, height now and a step ago, the shader blending the two to the frame's time), with the squares' own puddle
  level as Tessendorf's obstruction: the rings reflect off the puddle's shore and interfere.
- The other modes draw each footstep as the system's own impulse response, computed once at startup
  (`RipplesModel.build`, ~60 ms on a worker, 160 ms on the flip): per wavenumber the scheme's recurrence with the kernel's
  spectrum, the Gaussian footstep's spectrum, the radial slope as a Hankel transform (J1), into a 256 x 64 table
  slope(r, t). Checked against the 2D simulation (prototype `scratchpad/proto/hankel.py`): same crests, same signs. Rings
  superpose exactly on open water; only the shores need `sim`.
- `alu` (the default) uses a closed form fitted to that table instead of fetching it: ripples filling a disk of radius
  `1.12 t^0.7 + 0.06` squares, carrier 27 rad/square chirped 1.7x shorter at the centre, phase speed 0.9 squares/s,
  amplitude `0.55 e^(-1.2 t)` / sqrt(1 + 4 r), faded over the last third of the 1.8 s life. Side by side with the table
  (`fit5.png`, in-game `rip-look6`) it reads the same; a pointwise fit is impossible (the phases), the visual one is easy.

Dropped on the way: a Gaussian wave packet (thin double lines), the plain 2D wave equation (2D waves leave a wake: a
blob at the footstep swamps the ring), the Cauchy-Poisson stationary-phase formula (singular at the centre, aliases).

## Sources

Every character on a wet outdoor square (`getPuddlesInGround() > 0.09`: the game's own puddle level) starts a ring at
each stride (0.55 squares walking, 0.85 running), the feet 0.09 squares either side of the line, the ring backdated to
when the stride really ended (so looking at a crowd only every so often costs no timing). Vehicles: the rear wheels,
spacing `speed x 0.06 s` (0.3..1.5 squares), 1.8x the size. The puddle shader adds the ring slope to its wave normal `gm`
before HDR's glints and Ssr's reflections read it, and lets crests catch the light (an overcast sky mirrored in rain is
flat: the tilt alone hardly shows), only where the puddle shows (`levelf * puddlesSize > 0.28`).

- Zombies tell the ripples themselves: a hook at the end of `IsoZombie.update` (the zombie hot in cache). Armed only when
  the last frame drew puddles with ripples on; in a crowd each zombie looks once per ~0.4 s (`(frame + id) % N`), longer
  when the budget drops most footsteps anyway (at most 1.5 s). Players, animals (the object set walked every 2 s) and
  vehicles (the moving ones, listed twice a second, plus the local players' cars) from `gather`.
- **Budget** `ripplesPerSec` (160): Louisville's horde in flooded streets offered ~700 footsteps a second; past the budget a
  footstep passes with p = budget / rate (smoothed), decided before the square lookup; your own always pass.

## Modes and what each costs

GPU = the puddle pass's GPU section (+ the mode's compute section) minus the `off` phase; render / game thread = the same
differences of the render thread's issue time and of `Ripples.gather` (the hook's own time separately). Stress rig:
desktop RTX 4090, 5120 x 2160 at max zoom (12800 x 5400 offscreen, 6.25 offscreen pixels a screen pixel: the worst
case), `puddles=0.8 weather=clear` (puddles everywhere), still camera (`route=E:0 hold=60`), 40 seeded walkers (~190
rings in 16 x 16 squares), uncapped, defaults options file, modes interleaved every 400 ms, ~1,500-3,000 frames a mode.
Run-to-run noise of a walking-camera rig was +-10 us: only this interleaved still rig ranks modes.

| mode | how | GPU (4090) | GPU (flip 890M) | render | game |
|---|---|---|---|---|---|
| `loop` | every pixel tests every ring | +1220 us | | +2 | ~0 |
| `tiled` | rings binned into 128 px view tiles each frame (8 a tile, newest first), pixel reads its tile, profile fetch a ring | +11.5 -> 9.8 (hoist) | +46 -> 29.7 (hoist) | +4 | ~0 (binning on a worker) |
| **`alu`** | tiled, the profile's closed form (no fetch a ring) | **+11.1 -> 9.25 (hoist)** | **+24 -> 19 (hoist)** | +4 (flip +18) | +1.4 (flip +5.5: `Math.exp` a ring) |
| `world` | rings binned once for life in 2 x 2-square world tiles, one RGBA16 texel a ring | +16.5..24 | +34 | +4 | ~0 (births / deaths only) |
| `field` | compute evaluates the ring tiles at half resolution into an atlas, pixel reads 2 texels | +37 (compute 13.5) | | +15 | binning |
| `sim` | iWave on the GPU, shores | +24..27 (compute 6.4) | +68 | +10 | +2 |

The steps that mattered, in order:
1. **Binning** (`loop` -> `tiled`): 1.2 ms -> 56 us.
2. **The gate** (`rip-cost5`): every puddle pixel of a binned mode paid a tile fetch, ~40 M a frame at max zoom: texture
   throughput, most of the cost. A uniform box (view px; true world squares for `world` / `sim`) tested before any fetch:
   tiled 56 -> 10.6, alu 53 -> 9.3.
3. **Binning off the game thread**: ~8 us a frame for ~190 rings (rewritten first: dirty-tile clear instead of a 44 KB
   fill, ellipse test, counters, row-band upload 44 -> 3-4 KB a frame), then on a worker (`Ripples.Binner`, polling every
   0.5 ms: an `unpark` a frame was a futex call on the game thread); the render thread bins itself if it gets there first
   (`bin_stolen`, 0 in every run). Game thread: tiled +9.5 -> +0.
4. **Closed form** (`alu`): no texture fetch a ring. Same on the 4090, half on the flip (fetches are what the iGPU pays).
5. **Hoisting** (`rip-cost11`): what depends only on a ring's age (alu: its radius `t^0.7`, decay, fade, phase; profile
   modes: the reach and the table row) is computed once a ring a frame into a second uniform array (`pzRipQ`, so 96 rings
   a frame: two arrays of vec4 within the 1024 fragment components GL guarantees) instead of by every pixel: 4090 alu
   11.1 -> 9.25 us, tiled 11.5 -> 9.8.
6. **Real crowd** (Louisville preset in rain, ~450 zombies on screen, `rip-horde*`): the ring budget (700 -> ~90 rings a
   second started, pool and tiles stop saturating: 546k evictions and 250 KB a frame of uploads before), then the zombie
   hook (walking the zombie list: ~25 us a frame of cache misses -> hook thinned to ~45 looks a frame), then the moving-
   vehicle list (the town's parked cars were walked every frame): `gather` 35 -> 6.4 us a frame with ripples off-phase
   and on alike, i.e. what remains is the fixed part (players, the ring list, the hand-off). GPU in the horde: tiled
   +1.6..+15, alu +13, world +60 (it draws every live ring; the gate cannot help a crowd spread over the screen).

Also tried and kept as options: `world` mode (no per-frame binning at all) with one-square tiles (+24.7, finer grid,
worse) and staged listing (`devRipplesWorldStage`: young rings in fewer tiles, +28 vs +16.5: worse); `field` (half
resolution decoupled shading: the compute is cheap, the two dependent fetches are not). Not done: GPU binning (the
binning left the game thread anyway; a dispatch would cost render-thread time), temporal reuse (the waves move every
frame), per-quad vertex culling (the gate already removes most fetch-only pixels).

## Traps

- `packed` is a reserved word in GLSL: NVIDIA accepted it, glslangValidator and Mesa do not (RipplesTest compiles every
  shader; the flip's Mesa compiled all of them).
- A ring stored as a float of the clock was compared with the double clock: the newest ring's age was negative, which
  truncated the live list (`maxLive=18`).
- A dev A/B that processes `world` deaths only in its own phases piles rings up in the others: deaths run every frame.
- `Math.pow` per ring per frame (the alu cull radius) was ~10 us on the game thread: a table.
- A console line costs ~1 ms on the game thread: the A/B's phase changes are logged in batches.
- The Louisville preset draws never-seen blocks black: good for costs, useless for looks (`siege=N` rig for looks).

## Rain, and the Workshop card (2026-10-11)

In rain the game makes most of the road "dark wet ground" covered in its own raindrop rings and raises the puddles' mud
(`FLOAT_MUDDYPUDDLES`), which takes their reflection away: a footstep's rings drowned (Louisville / siege rain runs). Two
changes: the rings scale with the puddles' rain intensity (`RAIN_BOOST`: x (1 + 0.75 rainIntensity), up to 2.5x in full
rain, 1x dry: a footstep stirs a puddle more than raindrops do), and the harness scene flag `puddle_mud=V` pins the mud
(0 = clear puddles in rain, as in a shower's first minutes) for the card. The New! card (`harness/ripples-card.py`,
`ripples-changes.py`; `workshop-media/puddle-ripples.avif`, 630 px, 15.8 s, CRF 48 = 2.1 MB: rain streaks and raindrop
rings made CRF 35 6 MB, SSIM 0.90 -> 0.85 with no visible loss of the rings): the player walking in rain (zoom 0.5, runs
`ripcard-walk-{stock,enh}`), then a car at 30 km/h with its wake (zoom 0.75, `ripcard-drive3-{stock,enh}`; the game's
camera leads a moving car, so the capture sits left of the screen centre; the stock capture has a 3.6 s hole at route
8.1 s, the window ends before it). Stock = `--prop enabled=false`.
