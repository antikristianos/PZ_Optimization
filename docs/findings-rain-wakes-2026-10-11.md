# Raindrop rings and car wakes (2026-10-11)

Discord suggestion "Improved Rain + puddle Rendering" (2026-10-09), items 5 and 6: replace the game's raindrop rings with
our own ripples, and give a car a continuous water trail instead of a dotted line of wheel rings. Objective (maintainer):
implement every current technique one by one, profile each, at virtually no frame cost. Worktree `~/pzopt-wt/rain-wake`,
branch `rain-wake`.

Code: `pzopt.RainRipples` (raindrops), `pzopt.Wakes` (car wakes), hooked through `pzopt.Ripples` (the frame, the dev A/B,
the puddle shader patch, the uniforms). Keys (Enhancements tab, with `ripples`): `ripplesRain`, `ripplesWake`,
`ripplesRainPct`. Dev: `devRainModes` / `devWakeModes` with `devRipplesAlternate=<ms>` (GPU sections `puddles.rain_<mode>`
/ `puddles.wake_<mode>`; the compute passes `ripples.rain` / `ripples.wake` count into the next puddle section),
`devRainScalePct`, `devRainAmpPct`, `devRainLitPct`. Tools: `harness/ripples/cost.py <run> --base rain_stock|wake_rings`,
`harness/ripples/phaseframes.py <run> <out>` (a tone-mapped crop or clip per phase from the recording). Test:
`tests/pzopt/RipplesTest.java` (every new shader through glslangValidator, the two bakes).

## Verdict

- Raindrops: `ripplesRain=flipbook` (default): real drop rings at random spots instead of the stock grid of black half
  rings, filtered when zoomed out (at max zoom they average out to the wet surface, where stock shows aliased speckle),
  and 20-33 us *cheaper* than the stock rings at 5K on the 4090 (two fetches and no transcendental against ~48 a pixel).
  `field` (-8..-12) and `sim` (+10, real interference, busier) are the alternatives; `cells` / `texture` the references.
- Car wakes: `ripplesWake=trail` (default): a continuous wake (two refilling grooves, waves running out of them) for any
  speed, ~13 us of GPU while a car drives through water at 5K with puddles everywhere, nothing otherwise. `kelvin` (the V
  wedge) costs the same, `particles` ~15 us of compute, `sim` nothing on top of the footsteps' simulation.
- Not measured: the flip (disconnected all day), the Mac (macOS keeps `rings` for wakes: no compute; the raindrop
  flipbook runs through CoreGl's translation, unverified there).

## What the game draws

`puddles_common.frag.glsl` `getRain` (HQ): the screen (in the puddle's own world-anchored px) cut into 25 x 12.5 px cells,
two layers; in each of the 2 x 2 nearest cells a ring at the cell's fixed spot, pulsing every 1.67 s, radius up to 1.8
cells x the rain intensity (0..2): up to 3.6 cells, so every ring is cut at the 2 x 2 block's edge. The slope goes in as
`gm += rainWaves.xxy * 0.3` (the x slope in x and y, the y slope in z) and the shade as `rainWaves.z * 0.11` with
`z = 1 - |100 circles|^2`: on a crest about -100, so the colour clamps to black. What a player sees is a fixed grid of
black half rings (Discord: "the vanilla droplets looked weird"). Per pixel: 8 cells x (2 hashes of 2 sins + 2 sins).

## The ring

One ring for every raindrop mode: the footstep's closed form (pzopt.Ripples, fitted to the iWave impulse response) scaled
S = 0.4 in space and sqrt(S) in time (gravity waves: a ring S times the size lives sqrt(S) times as long): crests 0.09
squares, the ring 0.7 squares at the end of its 1.14 s. A real drop's ring is finer than a pixel at any of the game's
zooms; at S = 0.25 (first smoke) the crests were 2.6 px tall at zoom 1. Every crest is filtered for the pixel's footprint
(a Gaussian pixel: the carrier times exp(-(k sigma)^2 / 2)), so zooming out fades the rings instead of aliasing them.

Visibility: the stock puddle mixes its normal's effect into the colour at ~9 % (`mix(dark, reflection, 0.3)` at alpha
0.3); the rain rings (and the wakes, at half) also catch the light at the end of the shader, signed (one flank of a crest
brighter, the other darker), adding a little alpha on the crests.

## Raindrop modes

| mode | technique | per pixel |
|---|---|---|
| `stock` | the game's | 8 cells, ~48 transcendentals |
| `none` | no rings (what stock costs) | - |
| `cells` | procedural world-anchored cells (1 square), a drop a cycle at a new hashed spot, 4 layers by intensity | 16 cells, hash + cull, ring maths where in reach |
| `texture` | Lagarde's ripple texture (Remember Me 2013, UE's rain ripple function), two drop sets a texel (16-bit offsets), phase / rank hashed from the drop's centre | 2 fetches, ring maths |
| `flipbook` | the whole superposed field over one cycle, 32 frames, two layers RGBA8, mipmapped 3D texture (time interpolated by the hardware, mips the pixel filter) | 1-2 fetches |
| `field` | texture-space shading: compute evaluates the cells into a 320^2 periodic tile each frame | 1 fetch |
| `sim` | periodic iWave (the footstep sim's 13 x 13 kernel, gravity scaled for the drop size), drops as Gaussian impulses: real interference | 4 fetches |

Cost: the puddle pass's GPU section (plus the mode's compute pass) per frame, desktop RTX 4090, 5120 x 2160, full rain,
`puddles=0.8 puddle_mud=0`, still camera, uncapped, the defaults options file, modes interleaved every 400 ms
(`devRainModes=stock|none|cells|texture|flipbook|field|sim`), ~1,300 frames a mode (runs `rw-cost-rain-max`,
`rw-cost-rain-z1`):

| mode | max zoom: us | vs stock | zoom 1: us | vs stock |
|---|---|---|---|---|
| `none` | 192 | -30 | 254 | -27 |
| **`flipbook`** | **203** | **-19** | **248** | **-33** |
| `stock` | 222 | 0 | 282 | 0 |
| `field` | 228 | +6 | 270 | -12 |
| `sim` | 235 | +12 | 263 | -19 |
| `texture` | 256 | +34 | 301 | +19 |
| `cells` | 430 | +208 | 552 | +270 |

The stock rings cost ~30 us of the 5K puddle pass; the flipbook (two texture fetches, no transcendental) is cheaper
than them, inside the noise of drawing none. `cells` is the pure-ALU reference: 16 cells a pixel with the ring maths for
the ~7 in reach, every puddle pixel of the screen; `field` is the same maths once a frame on a 320^2 tile (+6), the
texture-space decoupling that makes it free.

## Wake modes

| mode | technique |
|---|---|
| `rings` | the first pass: a footstep ring at each rear wheel every 0.3 - 1.5 squares |
| `trail` | interaction render target: a world-anchored toroidal trail map (128 squares, 4 texels a square, RGBA16); each moving car stamps, per frame, the slab between its last two rear-axle lines (no gap on a turn): pass time, offset across the line, velocity. The pixel turns (age, offset) into two tyre grooves refilling and a wave group running out of each, dispersing (closed-form slope) |
| `kelvin` | the same map; the profile Kelvin's wedge (19.47 degrees; transverse and divergent waves from the stationary-phase roots tan psi = (1 -+ sqrt(1 - 8 tan^2 theta)) / (4 tan theta)), each filtered for the pixel |
| `particles` | wave particles (Yuksel, House, Keyser 2007): tyres shed packets to both sides; births only are uploaded, the GPU moves them, bins them per square (atomics) and gathers a slope map |
| `sim` | wheel impulses every 0.15 squares into the footsteps' iWave sim: the moving source's own wake, shores included |

All wake passes are compute dispatches (no framebuffer, viewport or blend state touched mid-world); macOS (GL 4.1) keeps
`rings`.

Cost: the 30 km/h flooded drive (`puddles=1`, zoom 0.75, 5K, uncapped, rain rings off), modes interleaved every 400 ms,
~650 frames a mode a run, two runs a step; delta against `rings` (which itself draws its wheel rings); `comp` = the
wake's compute / upload section:

| step (runs) | trail | kelvin | particles | sim |
|---|---|---|---|---|
| first (rw-cost-wake2/3) | +26 (comp 5) | +27 | +48 (comp 21) | 0 |
| occupancy texture gate, 2-square cells, particles outward only, closed-form trail slope (wake4/5) | +27 | +28 | +38 (comp 14.5) | +1 |
| 8-box ALU gate per pixel (wake6/7) | +36 | +34 | +46 | +1 |
| the gate per vertex: the puddle vertex unit tests the boxes, a varying (wake10/11) | +21..27 | +26..31 | +35..40 | 0..2 |
| no wake drawn at all (devWakeNoDraw, wake13) | +17 | +15 | +30 | 0 |
| **the compute at the frame's start (wake14/15)** | **+13..15 (comp 5)** | **+15..17** | **+13..17 (comp 15)** | **-3..-5** |
| the slabs stamped on the CPU and uploaded instead (wake16/17) | +13..14 (comp 4) | +12..16 | +16 | -5 |

What it took: the per-pixel work was never the problem it looked like. With no wake pixel drawn at all the trail still
cost +17: a compute dispatch (or a texture upload) queued right before the puddle draws waited for the graphics before
it and its barrier emptied the texture caches the puddles then refilled (pzopt.EntityShadow measured the same, ~21 us
a dispatch mid-frame). Run at the start of the next frame's world render (`Ripples.frameStart`, the puddles read the
result one frame late) the same work costs its own section and nothing else. Per pixel, an ALU gate over 11 M pixels
(8 boxes) cost more than the fetch it saved (~0.27 us a frame per instruction a pixel at 5K on the 4090, ~0.8 us per
fetch); the gate moved to the vertex unit (~10 k vertices) costs nothing per pixel. The ~4-5 us section floor is the same
for a compute dispatch and a texture upload: CPU stamping (`devWakeStampCpu`) is kept as an option, compute is the
default (no game-thread rasterising).

Result: `trail` (the default) ~13 us of GPU on a 4090 at 5K with puddles everywhere, only while a car drives through
water; `kelvin` the same; `particles` 15 us of compute (bin + gather over the box) and ~nothing per pixel; `sim` free on
top of the footsteps' sim (its rings and wakes then run in the simulation).

## Measuring

`cost.py` (since this pass): the footsteps' field / sim compute runs inside the puddle pass's GPU section (Begin): the
first pass's tool added it a second time, so `docs/findings-puddle-ripples-2026-10-10.md`'s `field` (+37, compute 13.5) and
`sim` (+24..27, compute 6.4) were overstated by their compute time. The rain / wake compute now runs at the frame's
start, outside that section, and is added. A GPU section with any work in it measured ~4-5 us here (a dispatch or a 6 KB
upload alike).

## Traps

- `GL.getCapabilities()` throws on the game thread (no context): the first smoke's field / sim phases threw 1,648 times
  (each a dropped puddle frame). The render thread caches it (`RipplesGpu.gl43`).
- `& (N - 1)` wraps only power-of-two sizes: the rain sim at N = 320 drew a lattice of square rings.
- The Bash tool runs zsh: an unquoted `$ARGS` stays one word and run.sh refuses it (two queue jobs lost an hour).
- The flipbook's `sampler3D` in the puddle unit: the game validates the program with every sampler on unit 0 and Mesa
  refuses two sampler types on one unit (the program dropped: no puddles); `layout(binding)` fixes Mesa, but NVIDIA's
  GLSL 1.20 refuses `layout()` even with 420pack. `Ripples.patchShader` compiles the bound variant and falls back to the
  plain one; `RipplesLinkTest` links and validates both units on the driver and on Mesa llvmpipe (`/tmp/glslcheck`).
- The puddle vertex unit has two versions (stock, puddleEarlyZ's `pzopt_` copy that rewrites the vDepth line): the wake gate
  anchors on a line both keep, and the fragment unit reads the varying only when the vertex units take it (an unwritten
  varying fails the link on Mesa).
- A look run's route ends where its path does (140 squares at 30 km/h: 17 s): a wake that "vanished" was a parked car.
