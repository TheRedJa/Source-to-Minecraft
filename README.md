# src2mc

**Play Source Engine maps in Minecraft 1.21.1, looking, sounding and working
the way they do in the original game.**

src2mc converts Source Engine maps (`.bsp`) into Minecraft. Its companion
NeoForge mod draws each map's exact geometry with its real textures, real
prop models and the light the map was compiled with, on top of ordinary,
editable blocks. Source's own HDR look, sounds, particles and map logic come
along too: buttons press, doors open, lifts carry you, voice lines play and
the map's scripted sequences run.

It was built for recreating the Half-Life 2 universe (Half-Life 2,
**Entropy: Zero** and **Entropy: Zero 2**). **Portal**, **Portal 2** and
**INFRA** maps are the everyday in-game test maps.

**At a glance** -- a Rust converter plus a NeoForge companion mod that give you:

| | |
|---|---|
| **Geometry** | Exact Source surfaces drawn on ordinary, editable blocks; sub-block (1/16) collision; displacement terrain |
| **Looks** | Real Source textures (own atlas, mipmaps), every prop as its real mesh with skins and tints, the map's baked Source light, bump maps, cubemap reflections, detail textures, blended terrain, self-illumination |
| **HDR** | Source's auto exposure, bloom, colour correction, vignette and fog; the 2D and 3D skybox |
| **Effects** | The game's own `.pcf` particles, impact effects, bullet holes and sparks |
| **Performance** | Source's PVS, frustum and GPU occlusion culling, batched props, threaded mesh building |
| **Sound** | Soundscapes, ambient sounds, music, voice lines and per-material footsteps, re-encoded to Ogg; captions |
| **Logic** | Source's entity I/O on the server: triggers, buttons, doors, relays, timers, level changes, `point_template`, scripted text and screen effects |
| **Motion** | Doors, lifts, trains and conveyors as Sable sub-levels you can ride; animated props |
| **Input** | BSP v19/20/21/22, loose files or inside VPKs; in-game tested on Portal, Portal 2 and INFRA maps |
| **Scale** | ~80k lines (Rust converter, Java mod), 600+ Rust tests, campaigns of many maps in one bundle |

### How it can look:
<img width="3440" height="1440" alt="Screenshot_20261006_234201" src="https://github.com/user-attachments/assets/1858758d-abe2-4419-92bd-0215d2c37b53" />

Running at 3440x1440p on an rtx 3060 with 290fps<br>
<br>
map: infra_c4_m2_furnace

#







There are two ways to get a map into Minecraft:

- **The companion mod (current).** `src2mc mod export` writes one campaign
  bundle plus one schematic per map. The NeoForge mod in [`mod/`](mod/) loads
  the bundle and draws everything described below. This is where development
  happens.
- **Plain schematics (legacy).** `src2mc convert` and `batch` voxelize a map
  into vanilla blocks, or into KubeJS-generated textured blocks, with nothing
  needed beyond WorldEdit. See [Plain schematics](#plain-schematics-legacy).

## The companion mod

### Features

**Geometry and building**

- **Exact surfaces on editable blocks.** Every brush face is clipped into the
  block cell it lies in and drawn at its true Source position, so walls, door
  frames and low ceilings sit exactly where they do in the game instead of
  snapping to half blocks. The blocks underneath stay ordinary blocks: break
  one and the geometry it owns goes with it; place blocks and build as usual.
- **Sub-block collision** for map geometry and props, in sixteenths of a
  block: you walk on the floor you see, under the ceiling you see and along
  railings instead of invisible cubes.
- **One-command placement.** `/src2mc place <map>` builds a map in the world,
  tick-budgeted, at a fixed 32 Source units per block. Whole campaigns can be
  placed side by side, and level changes take you to the next map, lined up
  on its landmark.

**The Source look**

- **Real textures** at up to 16 texels per block by default, or every texture
  at its original resolution with `--quality full`, on the mod's own mipmapped
  atlas pages. Fences and grates stay see-through with distance; two-sided
  materials draw from both sides.
- **The map's own baked light.** Lightmaps exactly as vrad compiled them,
  static props with their per-vertex light, and moving or animated things lit
  by the map's ambient light samples where they stand. Torches and other
  Minecraft lights still add their light on top. Players, mobs and items in a
  map take on its light too.
- **Source's surface shading.** Bump-mapped and self-shadowing (`$ssbump`)
  lightmaps, `env_cubemap` reflections with their masks, tint, contrast and
  Fresnel, detail textures in all of Source's blend modes, blended
  displacement terrain (rock fading into dirt and moss), and glowing
  self-illuminated signs, screens and indicator lights on surfaces and props.
- **HDR, as the engine does it.** Auto exposure that adapts as you walk from
  dark rooms into bright halls, bloom, the map's colour correction, vignette,
  and Source's distance fog, all driven by the map's own settings and its
  `env_tonemap_controller` and fog inputs.
- **The sky.** The map's 2D skybox and its 3D skybox room (the scaled-down
  city, mountains or smokestacks on the horizon) are drawn behind the map
  exactly where Source draws them.
- **Particles and effects.** The game's own `.pcf` particle systems run with
  their sprite sheets and blend modes: fire, smoke, steam, sparks, ash.
  Hits from Minecraft projectiles and TacZ
  guns play the surface's own impact sound and effect and leave the game's
  bullet holes, and `env_spark`s spark as they do in Source.

**Props and animation**

- **Every prop, as its real mesh**, at its exact Source origin and angle, with
  its skin, its colour tint (such as Portal 2's orange and blue gel tubes) and
  every material slot. Props are merged into per-section batches, so millions
  of triangles cost a few hundred draw calls.
- **Animated props.** Dynamic props play their model's animations: buttons
  press in, levers flip, doors slide, clocks and windmills turn, with
  collision following each pose. Lamps switch skins, and props retint, show,
  hide and disappear as the map's logic tells them.

**Sound**

- **The map's soundscapes**, picked the way Source picks them and crossfaded
  as you walk between them, its ambient sounds (machines, fans, fire), music
  and voice lines with Source-style captions.
- **Material sounds.** Footsteps, landings, hits and breaks sound like the
  surface's own material, replacing Minecraft's step sounds inside a map.
  Sound plays through Minecraft's sound engine, so the volume sliders apply,
  and [Sound Physics Remastered](https://modrinth.com/mod/sound-physics-remastered),
  if installed, adds reverb and occlusion.

**Map logic and movement**

- **Source's entity I/O runs on the server** by the SDK's rules: triggers fire
  as players walk through them, buttons and doors answer the use key, relays,
  branches, counters, timers and cases pass the chain along, and choreographed
  scenes play.
- **Moving brushes are real moving platforms.** Doors, buttons, lifts, track
  trains and rotators, with the props attached to them, move as
  [Sable](https://github.com/ryanhcode/sable) sub-levels: you collide with
  them, ride lifts and trains and get pushed by closing doors. Children ride
  their parents, and props can be re-parented at runtime.
- **`point_template`s** spawn fresh copies as in Source, so a conveyor keeps
  sending belt modules and debris along forever.
- **Screen effects.** `game_text` messages, screen fades and shakes, and
  INFRA's chapter titles appear as in the game.

**Performance**

- Source's own visibility data (PVS), frustum culling, GPU occlusion queries
  for props, indexed vertex buffers and nearest-first draw order. Meshes build
  on worker threads, so a world with several large maps loads in a second or
  two.

### Usage

```sh
# One bundle for a whole campaign, one schematic per map. Fixed scale:
# 32 Source units per block.
src2mc mod export --campaign infra -o out/ maps/infra_c4_m2_furnace.bsp

# Maps inside a VPK work too.
src2mc mod export --campaign infra -o out/ \
    infra/pak02_dir.vpk:maps/infra_c4_m2_furnace.bsp

# Every texture at its original resolution: sharper up close, but far larger
# bundles and much more texture memory (the furnace goes from 36 MB and one
# atlas page to 1.1 GB and 49).
src2mc mod export --campaign infra -o out/ --quality full maps/infra_c4_m2_furnace.bsp

# Leave the sound out: no soundscapes, ambient sounds or surface sounds.
src2mc mod export --campaign infra -o out/ --no-audio maps/infra_c4_m2_furnace.bsp

# Print how long each export stage took.
SRC2MC_TIMINGS=1 src2mc mod export ...
```

In a NeoForge 1.21.1 instance with the mod and [Sable](https://github.com/ryanhcode/sable)
installed:

1. Copy the `.src2mc` bundle into `config/src2mc/bundles/` and the `.schem`
   files into `config/src2mc/schematics/`. Bundles load in the background at
   startup; `/src2mc reload` picks up one that changed on disk.
2. Stand where the map's anchor should go and run `/src2mc place <map>` (the
   map id, e.g. `infra_c4_m2_furnace`). Placement fails loudly if the map does
   not fit the world's build height; tall maps need a dimension type with more
   height (see [Scale and world height](#scale-and-world-height)).
3. Run `/src2mc logic start` to start the map's logic as a new game.

### Commands

**Map logic.** `/src2mc logic start [map]` (from a player or a command block,
acting on the placement holding or nearest the command's position) starts the
logic as a new game, and `/src2mc logic stop [map]` discards it. It advances
while a player is inside the map and the tick rate is not frozen (`/tick
freeze` pauses it). `/src2mc logic status` shows the clock, the queue and
every input no entity handled yet; `/src2mc logic list <filter>` shows
entities and their state; `/src2mc logic trace on` prints every output as it
fires; `/src2mc logic fire <target> <input> [parameter]` is Source's
`ent_fire`. `/src2mc_logic_show triggers|usable|all|off [filter]` draws the
map's brush entities as wireframes of their exact shapes (triggers orange,
level changes red, usable buttons and doors green, the rest grey).
`/src2mc place_chain <map> <count>` places a map and the maps its level
changes lead to, side by side.

**Look.** `/src2mc_look on|off` switches Source's post-processing, and
`/src2mc_look bloom|exposure|correction|vignette|fog|reflections|detail|blend|selfillum|bump on|off`
each part of it for comparison; `/src2mc_look status` shows the exposure and
the luminance it measured, and `/src2mc_look scale <x>|auto` holds the
exposure. `/src2mc_sky on|off|status` switches the sky, `/src2mc_particles`
the particles, `/src2mc_light` the lighting settings.

**Sound and screen.** `/src2mc_audio` reports which soundscape plays and what
else is sounding; `/src2mc_audio soundscapes|ambient|surfaces on|off`
switches each part, `/src2mc_audio steps <gain>` sets how loud footsteps play
relative to Source's own levels (default 2), and `/src2mc_audio captions
on|off` switches the captions. `/src2mc_screen status|clear` lists the texts
and fades on screen and takes them off.

**Moving things and props.** `/src2mc movers status` counts the moving
platforms and `/src2mc movers respawn` rebuilds them; `/src2mc_logic_props
status|draw on|off` and `/src2mc_anim status|draw on|off` count the props the
logic changes and the animated ones, and switch their drawing for comparison.

**Rendering diagnostics.** `/src2mc_prop_overlay_toggle` (live render, GPU
time and culling counters), `/src2mc_render_status`, `/src2mc_debug_face`,
and `/src2mc_cull pvs|frustum|shadow on|off`, `/src2mc_mipmaps on|off`,
`/src2mc_indexed on|off` and `/src2mc_draw_order sorted|unsorted` for A/B
comparisons.

Building the mod and running its development client is described in
[`mod/README.md`](mod/README.md). The bundle format is specified in
[`docs/format.md`](docs/format.md), the design and every decision behind it in
[`docs/decisions.md`](docs/decisions.md), and the current state of the work in
[`SESSION_HANDOFF.md`](SESSION_HANDOFF.md).

### Why a mod

The converter grew from stone WorldEdit schematics, through colour-matched
vanilla blocks, to KubeJS blocks with real multi-block textures and real prop
meshes. That proved the visual approach, but it does not scale to a campaign:
Half-Life 2 alone produced more than 9 GB of generated pack data and pushed the
vanilla block texture atlas to roughly 16k x 16k, which can prevent Minecraft
from starting on weaker GPUs. Blocks also force every surface onto a half-block
grid, which is invisible in a big hall and ruins a 64-unit corridor. The mod
loads deduplicated campaign data through its own bounded texture backend and
draws exact geometry, while maps stay ordinary, editable blocks.

## Plain schematics (legacy)

Working:

- Loads Source BSP v19/20/21, and v22 as used by INFRA's branch, from a file or
  from inside a VPK — some games ship no loose maps at all.
- Voxelizes brushes at a configurable scale (default 32 Source units per block).
- Maps brush contents to blocks: water, glass, grates, ladders; clip, areaportal
  and tool brushes are dropped.
- Chooses a block per surface from its material: glob rules first, then the
  texture's average colour. Ships with rules for Half-Life 2 and Entropy: Zero.
- Voxelizes displacement terrain, backed into solid so it is not a shell.
- Places **every prop** the map has — the compiled `prop_static` lump *and* the
  entity lump's `prop_physics`, `prop_dynamic` and friends: the fences,
  railings, catwalks, crates, cars, doors and lamps that fill a map's rooms,
  none of which is in any brush lump.
- Draws those props as their **real triangle mesh**, not as cubes, through
  NeoForge's OBJ model loader — so a forklift is a forklift. The map's rotation
  is baked into the mesh so the prop can be an ordinary block the chunk
  absorbs, rather than an entity redrawn every frame. Large ones get invisible
  barriers to stand on.
- Leaves out the **3D skybox room**, the scale model of the horizon that would
  otherwise convert into a second, wrongly-sized map.
- Optionally extracts the map's **real textures** and emits them as Minecraft
  blocks through a generated KubeJS pack, each texture **split across as many
  blocks as it really covers** in the map.
- Fits half-height and stepped geometry to slabs and stairs.
- Hollows out solid volumes so only surfaces are emitted.
- Writes Sponge Schematic **v3** `.schem` tiles plus a manifest and a WorldEdit
  paste script.
- Writes moving brush entities (doors, platforms, trains) to their own
  schematics, so they do not seal the openings they belong to.
- Dumps every entity to JSON with positions in Minecraft coordinates.
- Converts whole campaigns at once, laid out side by side, and emits a
  dimension datapack tall enough to paste them into.

Not implemented yet: Entropy: Zero 2's MapBase-specific entities.

### Usage

```sh
# What does this map contain, and what will converting it cost?
src2mc inspect  maps/ez2_c1_1.bsp

# What every material resolves to, and why.
src2mc materials maps/ez2_c1_1.bsp
src2mc materials maps/ez2_c1_1.bsp --stubs > my-rules.toml

# Which materials have a real Source texture behind them.
src2mc textures maps/ez2_c1_1.bsp
src2mc textures maps/ez2_c1_1.bsp --missing

# Entities only, no voxelization.
src2mc entities maps/ez2_c1_1.bsp --classname func_door -o doors.json

# Convert, split into 256-block tiles (the default).
src2mc convert  maps/ez2_c1_1.bsp -o out/ --units-per-block 16

# Bigger tiles, or the whole map as one schematic.
src2mc convert  maps/ez2_c1_1.bsp -o out/ --tile-size 1024
src2mc convert  maps/ez2_c1_1.bsp -o out/ --single

# A whole campaign, laid out side by side, with a dimension to paste it into.
src2mc batch    maps/*.bsp -o out/ --spacing 256 --emit-dimension

# With the map's own textures, as generated blocks (needs KubeJS).
src2mc convert  maps/ez2_c1_1.bsp -o out/ --textures kubejs

# Maps that ship only inside a VPK. `maps` prints each one already in the
# archive:map form every other command takes.
src2mc maps    infra/pak02_dir.vpk
src2mc convert infra/pak02_dir.vpk:maps/infra_c1_m1_office.bsp -o out/
```

Every command that takes a map takes either form. The search path for textures
and models is rebuilt from the map's own `gameinfo.txt` either way, so a map
read out of an archive resolves its content exactly like a loose one.

`--tile-size` accepts up to 32767, the schematic format's per-axis limit. The
practical ceiling is memory rather than the format: a schematic stores one entry
per cell including air, so a single file is capped at 400 million cells and
anything larger asks you to tile it. For reference, all of `d1_trainstation_02`
fits in one 596 x 196 x 912 schematic of 236 KB.

`inspect` first is the intended workflow: it is instant and tells you the block
dimensions, the Y range, and whether the map needs a custom dimension.

Output of `convert`:

| File | Contents |
|---|---|
| `<map>_x<i>_y<j>_z<k>.schem` | Sponge v3 tiles, aligned to a global lattice |
| `manifest.json` | Tile positions, sizes, block counts per block type |
| `paste.txt` | WorldEdit macro pasting every tile at its position |
| `entities.json` | Every Source entity with all its keyvalues in lump order (repeated outputs included) and Minecraft coordinates |
| `entities/<class>_<name>_<n>.schem` | Moving brush entities, one file each |
| `dimension/` | A datapack dimension sized to the map (`--emit-dimension`) |
| `kubejs/` | Generated textured blocks and their script (`--textures kubejs`) |

Paste with WorldEdit or FAWE: `//schem load <tile>` then `//paste -a -o`.

The `-o` matters. Each tile's absolute corner is baked into the schematic's
`Offset`, and `-o` pastes it there. Plain `//paste` places the clipboard
relative to wherever you are standing, so if you move between tiles they end up
scattered at different positions and heights. `-a` skips air so tiles do not
erase their neighbours.

### Scale and world height

The default is 32 Source units per block, two Hammer grid squares: the 72-unit
player becomes 2.25 blocks, close to vanilla Minecraft proportions.
`--units-per-block 16` doubles the detail and the block count, at the cost of
more blocks and coarser-scale textures needing larger tiles.

Many E:Z2 maps are tall. At 16 units/block `ez2_c4_1` needs 1090 blocks of
height and `ez2_c2_1` needs 1213 — far beyond vanilla's 384. This is not a
problem: a datapack `dimension_type` allows up to 4064 blocks
(`min_y` >= -2032, `height` <= 4064, both multiples of 16, `min_y + height - 1 <= 2031`).
Nothing is ever clamped or rescaled to fit; `inspect` reports the exact `min_y`
and `height` a map needs.

### Terrain, doors and whole campaigns

**Displacements** are Source's terrain: a brush face subdivided into a grid of
displaced vertices. They are a heightfield rather than a solid, so they are
voxelized as triangles — using an exact separating-axis test against each voxel,
since sampling leaves holes where a triangle crosses a voxel corner, and holes
in terrain are what you notice by falling through them. The resulting surface is
one voxel thick, so `solidify` drives it a few voxels further in, along the
surface's own inward normal rather than downwards: displacements make cliffs and
ceilings as often as ground.

**Props** are everything a map puts *in* its rooms: the fences and railings
along a platform, the catwalks over the canals, the crates, radiators, lamps,
signs, cars and doors. None of the geometry is in any brush lump, so a map
converted from brushes alone is an accurate but empty shell. They arrive by two
routes: `prop_static` does not survive compilation as an entity — VBSP writes
the placements into the `sprp` game lump — while `prop_physics`, `prop_dynamic`
and their relatives stay in the entity lump as ordinary entities with a `model`
key, so anything naming a `.mdl` counts. `d1_trainstation_02` places 345
between them. Each model's `.mdl`, `.vvd` and `.dx90.vtx` are read off the same
search path the textures come from and LOD 0 is flattened to triangles.

The `sprp` lump is read out of the file directly rather than through `vbsp`,
because `vbsp`'s version table is wrong for the later versions: from version 7
up it takes the four bytes at offset 64 as a word of flags, and in the maps that
actually exist those bytes are the minimum and maximum CPU and GPU levels, which
are `0xFF` apiece when unset. Every flag then reads as set, `NO_DRAW` included,
and the map's static props are thrown away — all 328 of a Portal 2 map, and 6691
of INFRA's 8386 in `infra_c1_m1_office`. The real flags are the byte at offset
31 and have not moved since version 4; neither have the origin, the angles or
the model index, which are the first 26 bytes of every version. Everything that
differs between versions and between branches comes after them, so none of it
has to be understood — only stepped over, at a stride measured from the lump's
own count and length rather than looked up from a version. That is also the only
thing that reliably separates branches which share a version number and disagree
about the record.

A prop is the one thing in a Source map that was never designed for a grid, so
by default it is not put on one. Its triangles are written as a Wavefront
`.obj` and registered as a block whose model NeoForge's built-in OBJ loader
draws — so a car is a car rather than a lump of mismatched cubes. Two
conventions bite here and both are handled: one OBJ unit is one block, not the
1/16 a vanilla JSON model means, and a pack texture is a sprite on a shared
atlas where UVs past `0..1` read whatever was stitched next door rather than
wrapping, so a model that tiles its sheet gets the texture repeated into a
larger image and its coordinates divided to match.

Placing it at the map's own angle is a separate problem, since a block sits on
the grid facing one of four ways. A `minecraft:block_display` entity solves it
by rendering a blockstate under a free transformation, and that is what src2mc
used to do — at a cost that turned out to be the whole frame budget. A display
entity goes through the entity renderer every frame and is never baked into a
chunk's vertex buffer, so a few hundred props in view is a few hundred thousand
triangles resubmitted per frame, whatever culling mods are installed.

So the rotation is baked into the mesh instead. Each placement gets a model
whose coordinates already carry the map's angle and its position within a
block, and the prop becomes an ordinary block placed in a free cell inside its
own geometry — chunk-baked, free per frame, and lit face by face rather than by
the single cell it stands in. Placements that round to the same angle and
offset share one block, so a row of identical fence posts is one registration.

A block model may be drawn outside its own block, but not arbitrarily far.
Sodium packs each chunk vertex coordinate into 20 bits spanning −8 to +24
blocks from the section origin and masks away what does not fit, so a mesh
reaching past that is drawn correctly up to the limit and then folds back on
itself — which is what a gantry or a light shaft did, as a black sheet folded
over the map. A block can sit anywhere in its 16-block section, so 8 blocks
either way is the reach that is safe wherever it lands, and a prop bigger than
that is carried by several blocks instead, each drawing the part of the mesh
nearest it. Triangles too wide to fit in any one piece — a light shaft is often
a single pair of them — are split at their longest edge first, which is exact
on a flat triangle. `d1_trainstation_02` ends up with 321 of its 325 props
baked and 4 still entities; across 140 stock Half-Life 2 and Entropy: Zero
maps, 45,676 props bake and 531 do not, and no generated model reaches further
than the 8 blocks it may.

Splitting is what it costs: `d1_trainstation_02` registers 2195 blocks for its
props where one block per placement was 258, and its pack grows from 86 MB to
107 MB. Registration count itself is cheap — KubeJS loads tens of thousands of
blocks in a fraction of a second — so what grows is disk.

The block never replaces anything: it only ever takes a cell that is already
air, since taking one of the map's own would be a hole in whatever the prop
stands against. A prop with nowhere to put a block — and anything over
`bake_max_size` — keeps the old route, a display entity written into the
schematics' `Entities` list and into a `.mcfunction` of `summon` commands at
the same absolute coordinates, everything tagged `src2mc_<map>` so a bad paste
is one `/kill` away. `bake = false` puts every prop back on it.

Neither route has collision of its own, so props are made solid separately, as
their Source `solid` setting says; `collision_min_size` (0 by default) can leave
small ones walk-through. That used to be a shell of invisible
barriers, one full cube per cell the surface passes through, which walks well
enough and is wrong in every detail — a catwalk floor three pixels thick
collides as a whole block, a railing as a wall. Physics mods make that worse
than untidy: Sable and Create: Aeronautics resolve contacts against block
shapes, so a map of cube-shelled props is a map of invisible boxes to catch on.

So each of those cells now gets a generated block shaped like the part of the
mesh inside it, found by clipping the prop's triangles to the cell and taking
what is left. Per cell rather than per prop, because Minecraft only tests blocks
within one block of whatever is moving: a shape describing geometry ten blocks
away is never consulted. The blocks are invisible — a blockstate pointing at a
model with no elements — and shared, since a shape is six numbers and thousands
of cells round to the same ones. `d1_trainstation_02` covers 44,762 cells with
8954 of them. Rounding is always outward, so a box is never smaller than the
geometry it stands for, and `collision_max_shapes` bounds how many distinct ones
a pack may register by rounding to a coarser grid until they fit — coarser being
more generous, never thinner. `[props] collision = "barrier"` asks for the old
cubes, `"none"` for nothing at all, and vanilla output uses cubes regardless,
having no pack to register a shape in.

Anything that cannot be
drawn as a mesh — a model heavier than `max_triangles`, a material with no
texture, or vanilla output, which has no pack to register meshes in — falls back
to the old behaviour of voxelizing the triangles, where a prop's material is a
material like any other and gets the same rules, colour matching and generated
block. Voxelized props are surfaces rather than solids, so a fence stays one
block thick.

`[props] min_size` can drop anything under a size (off by default: small props
are drawn as exact meshes, and a map's buttons and levers are small), and
`max_size` is the lever for backdrop scenery, which is placed as
ordinary props thousands of units across and can be tens of thousands of blocks
of one dark material. It is off by default, because that scenery really is
there.

**The 3D skybox** is a map's model of its own horizon: a sealed room off in a
corner holding a miniature of the skyline, which the engine renders scaled up
and far away. Converted literally it is a second, wrongly-sized map, and the
void between the two rooms is most of the schematic's volume. Nothing in the
format marks that room, but the `sky_camera` stands inside it and nowhere else,
so it is found as the smallest island of touching brushes enclosing the camera —
with a cap on how much of the map that island may be, and a check that no player
start is inside it, because dropping the level would be the worse failure. On
`d1_trainstation_02` leaving it out takes the bounding volume from 349M blocks
to 149M, halves the number of schematic tiles, and brings the map inside a
vanilla world's height. 123 of the 216 stock maps have one. Turn it off with
`[contents] skip_3d_skybox = false`.

**Moving brush entities** get their own schematic each, under `entities/`. A
`func_door` pasted into the world is a slab sealing the doorway it should open,
so doors, rotating doors, movelinears and tracktrains are pulled out by default.
Configure it per classname under `[entities.classname_modes]`.

**`batch`** converts several maps into one output directory, offsetting each so
none overlaps another, and writes a combined `paste_all.txt` and `batch.json`.
Offsets come from each map's own footprint rather than a fixed stride, because a
stride large enough for the biggest map strands everything else in empty space.
`--layout stacked` puts them all at the origin instead, for comparing versions of
one map.

**`--emit-dimension`** writes a datapack next to the schematics defining a
dimension with the `min_y` and `height` the map needs, plus a void generator, so
there is nothing to dig out before pasting. This matters because WorldEdit drops
out-of-range blocks *silently*: without it you paste a tall map, walk in, and
find the top missing with no error anywhere.

### Materials

Every surface gets its block from the material on the brush side that voxel is
nearest to. Two things decide it, in order.

**Rules** are ordered glob patterns; the first match wins. They exist for the
cases no colour could imply — bars must be bars, a ladder must be climbable,
glass must be see-through:

```toml
[[rule]]
match = ["*grate*", "*fence*", "*railing*"]
block = "minecraft:iron_bars"

[[rule]]
match = "wood/*"
auto = true          # match by colour, but only against wooden blocks
set = "wood"

[[rule]]
match = "tools/*"
skip = true
```

**Colour matching** handles everything else. It does not need a game install or
a VTF decoder: the map compiler already stores each texture's average colour in
the BSP, as the `reflectivity` radiosity bounces light with. That colour is
converted to Oklab and matched against a curated list of Minecraft blocks —
every one a full opaque cube with the same texture on all sides, that stays put
and does nothing on its own. No sand, no logs, no magma.

A rule's `set` narrows which blocks are eligible, which is what keeps wood
wooden while preserving how light or dark each texture is. That matters more
than it sounds: Entropy: Zero's metal ranges from near white to near black, and
a fixed `metal/* → iron_block` mapping flattens a whole map into one shade.

`src2mc materials <map>` shows every material with its colour, its block and
what decided it, busiest first. `--stubs` prints the colour-matched ones as
rules you can edit, and `--guessed` narrows the table to those. The built-in
rules route every material in all 170 stock Half-Life 2, Entropy: Zero and
Entropy: Zero 2 maps; only self-illuminated textures like monitors, which Source
stores no colour for, reach `fallback_block`.

Your own rules go before the built-in ones, so they win:

```sh
src2mc convert map.bsp --rules my-rules.toml -o out/
src2mc convert map.bsp --palette-set stone,concrete -o out/
src2mc convert map.bsp --no-builtin-rules -o out/
```

### Real textures, as real blocks

By default a wall becomes the vanilla block closest to its average colour. With
`--textures kubejs` it becomes the actual Half-Life 2 concrete.

Minecraft cannot add blocks from a resource pack alone — a pack only retextures
blocks that already exist — so something has to register them, and
[KubeJS](https://modrinth.com/mod/kubejs) is the least intrusive way: its
`kubejs/assets/` folder loads exactly like a resource pack, and a generated
startup script registers each block under its own namespace, so nothing vanilla
is overwritten. Both halves are written into the output directory:

```
kubejs/assets/kubejs/textures/block/<id>.png
kubejs/startup_scripts/src2mc_blocks.js
```

Copy that `kubejs` folder into a NeoForge 1.21.1 instance next to `mods`, and
restart — KubeJS cannot hot-reload registrations. **A schematic converted this
way will not paste correctly without its pack**, so the required block ids are
listed in `manifest.json` and `paste.txt` says so.

The textures are not in the maps. A BSP's pakfile holds mostly the cubemap
*patch* stubs the compiler generated — `az_c4_4` ships 22 `.vmt` and 5 `.vtf` —
while the real textures live in the game's VPKs and a mod's loose `materials/`
folder. The search path is rebuilt from the map's own `gameinfo.txt`, so
Entropy: Zero 2's chain through `ez2/`, `mapbase/` and `hl2/` resolves without
configuration. `src2mc textures <map>` shows what was found and where — for
brush materials and for the ones static props bring with them, with how far
each texture will be split — and it resolves nearly every material in use, the
rest being render targets like `_rt_Camera` and water shaders that have no
`$basetexture` at all.

Rules still win where the *kind* of block matters: a grate stays `iron_bars`
rather than becoming an opaque cube with a grate painted on it. Alpha-tested
materials are registered `cutout` and translucent ones `translucent`, and an
alpha-tested texture is re-thresholded when downsampled — averaging a grate's
alpha to 16x16 otherwise makes every texel part-transparent, which cutout
rendering draws as a solid block.

#### One texture, many blocks

A Source wall texture is not sized for one block. A 512-pixel concrete texture
at Hammer's default scale of 0.25 covers 2048 units of wall, which at 16 units
per block is eight blocks. Squeezing all 512 pixels onto every block face is
what made converted walls look like a smear.

A face does not store UVs; it stores two 4-vectors projecting a world position
straight into texel coordinates, and the length of each is texels per unit. So
the map itself says how many blocks one repeat of a texture covers. Each
texture is cut into that many pieces, one registered block apiece, and every
voxel takes the piece that really is in front of it — so the bricks line up
across the wall again.

**One tile is always one block.** A cap on tiles per axis is met by shortening
the *window* into the texture, never by widening the tiles. The alternative is
worse than the problem it solves: Highway 17's cliff blend spans 77 blocks, so
eight tiles stretched to fit would be flat ten-by-ten patches of identical
stone with a hard seam between them, which the eye finds instantly. Past the
cap only the first few blocks' worth of texels is used and that window repeats
— detail per block stays exactly right, and what is lost is the part of the
texture that never repeats anyway.

**The cost is capped by a block budget, not by a tile count.** What splitting
textures really costs is not disk — 100k blocks is about 60 MB of 16x16 PNGs —
but what a KubeJS instance pays to register them at startup. So the control is
`[materials] max_blocks`, default 100,000: textures are cut as finely as that
allows and no finer, which makes the same setting sensible for a single room
and for a whole campaign. `batch` plans one cap across every map before
cutting anything, since it merges them into a single pack.

Quality saturates well before the budget usually binds, because a texture is
never cut finer than its own resolution can feed — below one source texel per
output pixel a tile is upscaled mush rather than recovered detail. That is what
makes a generous ceiling safe: cost stops rising exactly where quality stops
improving. Entropy: Zero's 17 maps come to about 84k blocks with every texture
at full resolution, so the default lets the whole campaign through untouched.

**The tile size comes from the face, not the material.** One material is used
at several scales in the same map — Highway 17's `nature/cliffface001a` at six
of them, from a third of a texel per unit to two — so sizing tiles from the
material's typical scale leaves them too wide for every face using a larger
one, and that face comes out in 2x2 blocks of the same picture. Faces wanting
fewer texels per block than the tiles were cut at get the tile resized to the
block; faces wanting more are left exact, since advancing by more than one tile
per block shows no repeat.

Models have no texture scale to read — their UVs are an unwrap of the whole
sheet — so a prop's tile is interpolated across the triangle from its corner
coordinates, and how finely the sheet is cut is measured off the model's own
geometry rather than assumed. That matters more than it sounds:
`props_wasteland/rockcliff02a` stretches one sheet over 39 blocks of cliff, so
a fixed guess is out by a factor of several. A model's sheet is an atlas rather
than a repeating texture, so it is never windowed — a prop stretched past
`tile_max` keeps some repetition, which is what raising the cap buys.

Turn the whole thing off with `[materials] tile_textures = false`.

On `d1_trainstation_02` this is 198 materials registering about 20k blocks and
11 MB of 16x16 PNGs, with no measurable conversion cost.

### Sub-block detail

A block is a 1 m cube, so at 16 units/block every 8-unit step and kerb rounds
away. `[shapes] enabled` fits half-height and stepped geometry to **slabs and
stairs**, from a 2x2x2 occupancy mask recorded during voxelization and applied
after hollowing. On `d1_trainstation_02` that recovers about 7% of blocks as
slabs or stairs, with no measurable cost, and every schematic tool handles them
natively.

The mask is stored in the same sparse 16-cubed sections as the block grid.
Keying it per voxel in a hash map instead cost about 48 bytes each, which on
E:Z2's largest map was a 6 GB peak against 657 MB for the whole rest of the
conversion.

Only block families that have vanilla slab and stair variants can change;
anything else stays a full cube, and an unrecognised mask always stays a full
cube too — losing a step is far less noticeable than opening a hole in a wall.

This is vanilla-only. KubeJS 2101 registers blocks through exactly two
builders, `basic` and `detector`, so a generated textured block cannot be a
slab or a stair: `--textures kubejs` gives you the real textures and full cubes,
and vanilla mode gives you sub-block shapes.

Chisels & Bits was considered and rejected: every C&B block shares one id with
its shape in block-entity NBT, and WorldEdit copy/paste renders them invisible
([WorldEdit #2390](https://github.com/EngineHub/WorldEdit/issues/2390)). For
true detail everywhere, `--units-per-block 8` still works — E:Z2's tallest map
then needs ~2426 blocks of height, inside the 4064 a dimension allows.

## Configuration

Every setting lives in one TOML file, and anything omitted keeps its default:

```sh
src2mc convert map.bsp -c my-config.toml -o out/
```

See `example-config.toml` for the full set with comments.

## Notes on Source BSP handling

Five things worth knowing if you work on this code:

- `vbsp` sorts its `leaves` vector by cluster, so its leaf indices do **not**
  match the indices BSP node children reference. Associating brushes with the
  model that owns them requires original leaf order, so `src/bsp/rawleaves.rs`
  reads that one lump directly.
- Shipped maps are not always valid UTF-8. `ez2_assassin_demo` has non-breaking
  spaces typed into a light's `_ambient` value, which `vbsp` rejects outright.
  The entity lump is repaired in memory, byte for byte, before parsing.
- Material paths in a compiled BSP are not the paths that were authored. A face
  lit by an `env_cubemap` is rewritten to `maps/<map>/<path>_<x>_<y>_<z>`, and a
  displacement blend texture gets a `_wvt_patch` suffix. Two thirds of Entropy:
  Zero's materials are patched this way, so rules would be useless without
  undoing it. The two nest, too: `d1_canals_01a` contains
  `maps/d1_canals_01a/maps/d1_canals_01a/nature/blendmudmud001a_wvt_patch_-1624_6208_7`.
- A brush entity's geometry is **not in world space**. VBSP rewrites it to be
  relative to the entity's `origin` keyvalue and leaves the model's own stored
  origin at zero, so the coordinates in the plane lump have to have the entity
  origin added back. This is not a rare case: 103 of the 115 brush entity models
  in `d1_trainstation_02` are stored this way, and taken at face value every
  door, button, trigger and `func_brush` in a map piles up around wherever
  Source's origin happens to land.
- `gameinfo.txt` is not uniform across mods. Half-Life 2 writes `SearchPaths`
  unquoted, Entropy: Zero 2 writes `"SearchPaths"`, and E:Z2's content sits
  behind `|gameinfo_path|ez2/*` and `|all_source_engine_paths|mapbase/*`.
  Missing any of those finds nothing at all: before the parser handled them,
  E:Z2 resolved 0 of 127 materials rather than 125.

## Building

```sh
cargo build --release
cargo test --release
```

The mod builds separately with Gradle from `mod/`; see
[`mod/README.md`](mod/README.md).

Tests that need real maps look for an Entropy: Zero install and skip themselves
when it is absent.

A prebuilt x86-64 Linux binary is on the
[releases page](https://github.com/TheRedJa/source_to_mc/releases), zipped with
the licence, the third-party notices, this README and `example-config.toml`.

## License

Copyright 2026 TheRedJa. [PolyForm Noncommercial 1.0.0](LICENSE): free to use,
modify and share for any noncommercial purpose. Redistributing any part of it
means passing on the licence and the notice in [NOTICE](NOTICE).

The crates src2mc is built from are compiled into its executable and keep their
own licences — all permissive, none copyleft. Their terms are collected in
[THIRD-PARTY.md](THIRD-PARTY.md), regenerated for every release by:

```sh
cargo install cargo-about --locked --features cli
cargo about generate about.hbs -o THIRD-PARTY.md
```

src2mc ships no game content. Textures, models and maps are read out of your own
installation of the game, and nothing of Valve's is redistributed with the tool
or with this repository.
