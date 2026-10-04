# Current mod architecture decisions

Status: decisions for the clean mod restart described by
[`mod/IMPLEMENTATION_PLAN.md`](../mod/IMPLEMENTATION_PLAN.md). The abandoned
prototype is not a compatibility target. Historical measurements are retained
only where they constrain the new design.

When new evidence changes a decision, add a superseding entry that names the
old one. Do not silently make code and documentation disagree.

## D1 — Rust converts; Minecraft consumes

The Rust CLI remains the only Source BSP conversion implementation. The mod
does not parse BSP, VPK, VTF or MDL files and does not depend on the user's game
installation at runtime. It consumes exported schematics and a campaign bundle.

This keeps Source-specific parsing in the mature converter and makes installed
campaigns self-contained.

## D2 — One schematic per map and one ZIP bundle per campaign

WorldEdit schematics remain the transport for world placement. Shared assets
and metadata live in a versioned, custom-extension ZIP campaign bundle.

A batch conversion at the fixed mod-export scale of 32 Source units per block
emits one bundle plus one schematic per map. Content uses stable IDs and hashes
so textures and models shared by several maps occur once. Export preflights the
dense schematic dimensions and fails explicitly if an exceptional map cannot
fit; it does not silently tile, truncate or rescale the map. The exact v1 layout
is deliberately deferred to Phase 1.

## D3 — Content never creates registry entries

The mod registers a small fixed set of generic blocks, items and block-entity
types. Materials, models, maps, placements and collision shapes are runtime
data.

Minecraft registries freeze at startup. The earlier KubeJS route generated
content-specific blocks and therefore required restarts, huge file counts and
large registries. Reproducing that model inside the mod would preserve the
problem the mod exists to solve.

## D4 — A map anchor defines map-local lookup

Each schematic carries a map anchor at its original origin. At runtime anchors
map world positions back to bundle ID, map ID and map-local coordinates.

Surface cells are generic blocks with no per-cell block entity. Their material
and planar UV region come from the bundle's spatial data. This supports several
maps, and several independently translated instances of one map, without
encoding content identity in registered block IDs.

Carriers are derived state and are not authoritative. Anchors and prop roots
are authoritative and are included in the complete exported schematic.

The prototype supports ordinary unrotated, unmirrored WorldEdit paste only.
Rotation, mirroring, partial-map copy and post-paste WorldEdit editing are out of
scope. Rotation and mirroring are not detected and may render incorrectly if
deliberately used. Anchorless surface data is detectable and must diagnose
itself rather than guessing a coordinate transform.

The server owns and persists placement identity. The client receives the small
immutable placement-index view required for chunk rendering. This metadata sync
is required even in an integrated single-player game and is distinct from the
deferred problem of distributing campaign bundles or assets to multiplayer
clients.

## D5 — Preserve Source UV projection data

Surface texture coordinates come from canonical visible-face records exported
by the converter and evaluated in map-local space. Each record identifies its
cell and rendered face or fitted-shape surface, Source provenance, material and
full planar transform. Orientation, scale and phase are part of the data.

A single material or block value per voxel is not sufficient: one cell may
expose faces originating from different Source sides. Face provenance must
survive overlap resolution, hollowing and shape fitting before the bundle is
written.

Runtime lookup uses sparse 16x16x16 map-local section buckets. Faces carry
compact references to deduplicated material and UV-region records instead of
repeating a full planar transform. The inventory determines bounded integer
widths before format v1 is locked.

The abandoned prototype derived triplanar UVs from world position and stored
only `blocks_per_repeat`. That could shift a material by up to one repetition
and could not reproduce arbitrary Source texture vectors. The new format must
not inherit that approximation.

## D6 — The mod owns paged textures

Generated Source textures do not enter Minecraft's vanilla block texture
atlas. The first backend uses mod-owned atlas pages no larger than 4096 x 4096.

Half-Life 2 alone grew the KubeJS pack beyond 9 GB and drove the block atlas to
roughly 16k x 16k, causing startup failures on weaker GPUs. Multiple bounded
pages make capacity explicit and prevent one global texture from growing with
the campaign.

Bounded dimensions do not by themselves bound total VRAM. The backend is not
accepted until a real-content inventory establishes decoded RAM, mipmapped VRAM
and page-count costs and the prototype has an explicit residency budget.

A texture-array backend is a gated fallback if paged atlases fail the in-game
visual test; it is not silently substituted during implementation.

Each physical texture-region allocation lies wholly on one page. Logical
textures retain the resolution required for 16 output texels per projected
world block. `mod export --quality full` instead keeps every texture at its
original Source resolution (only an image larger than 4096 on an axis, the
client's output limit, is scaled to fit with its aspect ratio kept); this is
an export choice, not a format change, and costs far more atlas pages. A logical image larger than one page's usable area is partitioned
losslessly into page-contained regions; geometry selects the appropriate
region, so the image is neither squeezed nor downsampled to fit. Each region
has a 16-pixel base-level extruded gutter and the converter generates mip levels
0 through 4. A cut-out region's alpha is re-binarized in each smaller
level, keeping its full-size share of opaque texels, so fences and grates keep
their holes with distance instead of averaging to solid. The gutter therefore remains at least one texel through the final
mip and allocations are aligned to mip boundaries, preventing adjacent atlas
content from bleeding under filtering.

Decoded-RAM and estimated-VRAM residency budgets default independently to 2
GiB and remain configurable. They are cache limits, not permission to allocate
either amount eagerly.

Campaign metadata loads eagerly, while texture pixels and GPU pages are
demand-resident. Chunks approaching render distance acquire their pages; pages
with no loaded-chunk references become evictable after a grace period. RAM and
VRAM budgets use least-recently-used eviction under pressure. An asynchronously
loading page renders as a diagnostic placeholder and invalidates only its
dependent chunks when ready. Camera direction alone does not control residency.

The initial translucent fallback is deliberately basic: alpha-blended atlas
meshes render at NeoForge's `AFTER_PARTICLES` stage in coarse far-to-near
section/page order. It supports ordinary transparent pixels without claiming
Source shaders, decals, refraction, or correct per-triangle ordering.

Source's `models/effects/vol_light*.mdl` family is excluded permanently. Those
meshes represent shader-driven volumetric light rays rather than physical scene
props; rendering their raw triangles through the ordinary translucent path
produces elongated wedges instead of the Source effect. Actual windows, lamps,
and light fixtures are unaffected.

## D7 — Prop geometry is chunk-baked

Props are non-ticking root block entities whose model data feeds the chunk
mesh. No placement creates a permanent Minecraft entity, block entity renderer
or block entity ticker.

The root is invisible, non-colliding authoritative storage, not the visible
model and not the model's transform origin. During export it is placed in the
nearest free cell within or immediately around the transformed model bounds,
using a deterministic candidate order. The exact Source-derived transform stays
on the placement and is evaluated independently of the root cell. Export fails
with the prop's stable ID if no safe cell exists; roots never overwrite map
geometry, move the visible prop or disappear silently.

Earlier display-entity experiments lost substantial frame rate at a few hundred
loaded props, with cost worsening beyond roughly 500 entities. By contrast, a
converted map with more than 3,000 props and millions of triangles showed no
meaningful comparable frame cost once the geometry reached the chunk mesh.

Triangle count is therefore not an arbitrary acceptance budget. Correct
lifecycle and keeping work out of per-frame entity rendering are the priorities.

## D8 — Large prop rendering uses derived carriers

A prop has one authoritative root. Invisible carrier blocks divide rendering
across chunk/section space where needed and are rebuilt from the root after
placement, load or reconciliation.

In Sodium 0.8.13-beta.1, compact chunk vertex coordinates make approximately
eight blocks in either direction the safe reach regardless of the carrier's
position in its section. Geometry beyond that can wrap rather than merely clip.
Compatibility testing must confirm the constraint for the actual target
version before relying on the exact number.

## D9 — Prop collision is derived per cell

Collision is generated from the prop mesh and transform, conservatively
quantized, cached per chunk or section and exposed through carrier cells.

Vanilla and the tested Lithium implementation search only near blocks crossed
by the moving shape, so one oversized collision shape at the prop root is not
discovered across a large prop. The tested Sable implementation also
voxelizes world collision per block. Per-cell collision satisfies both.

Collision shapes are runtime data, never registered block variants.

## D10 — Roots and anchors degrade to repairable placeholders

Missing, corrupt or unsupported bundle data must not destroy the information
needed to recover. Map anchors and prop roots become persistent placeholders
that retain their IDs and source metadata.

After corrected content is installed, reload plus reconciliation heals them
without repasting the schematic. Diagnostics identify the bundle, map or asset
and use stable error codes for format-boundary failures.

Automatic reconciliation is lazy on authoritative server chunk load; the
manual `/src2mc reconcile` command provides an explicit repair pass. Neither
path depends on WorldEdit events or client render distance.

## D11 — Compatibility claims require the actual target environment

Minecraft 1.21.1, NeoForge and Java 21 are fixed. Claims involving Sodium,
Lithium, WorldEdit/FAWE, Create, Create: Aeronautics or Sable require their exact
target versions and the agreed test instance.

The placeholder table in `mod/docs/compatibility-baseline.md` is intentionally
not evidence. Manual screenshots, logs and benchmark reports remain local and
must not include proprietary game assets.

## D12 — The new format starts at Phase 1

The directory bundle, `surface_N` pool, `src2mc:FormatVersion` schematic field
and shared fixtures from the first prototype are abandoned. They impose no
compatibility or migration requirement on the new implementation.

Phase 1 defines the new ZIP manifest, JSON tables, binary mesh, schematic NBT,
version rules and error codes together. Until then, no checked-in document or
dead source file is a normative interchange contract.

## D13 — Content identity is derived from canonical entry payloads

Human-readable source names are provenance, not deduplication keys. Every
hashed table or asset type defines a canonical byte representation, including
float normalization and rejection of non-finite values. Content IDs derive from
those bytes. Bundle identity is a fingerprint of sorted entry paths,
uncompressed sizes and SHA-256 payload hashes. ZIP timestamps, permissions,
host metadata and compressed bytes are ignored by validation; whole-archive byte
identity is not a compatibility requirement.

## D14 — Reload publishes an immutable generation atomically

A candidate bundle generation is parsed, bounded, validated and prepared before
it becomes visible to lookup or rendering. Failure leaves the last known-good
generation active. Runtime handles identify their generation so asynchronous
chunk work cannot combine old tables with new textures or meshes.

## D15 — Per-cell collision preserves disconnected occupancy

The fixed collision quantization (4x4x4 as first planned; sixteenths for the
map's own geometry since D18) is represented as subcell occupancy, not
as one bounding box around every triangle fragment in a block. Disconnected
occupied regions remain disconnected. Runtime shapes may merge adjacent
occupied subcells into fewer boxes only when the represented volume is
unchanged.

## D16 — Rendering uses mod-owned static section meshes

Surface and static-prop geometry is baked into mod-owned immutable GPU buffers
partitioned by Minecraft section and texture page. The client submits visible
section/page buffers from NeoForge's world-render stages using the supplied
camera frustum. It creates no entity or block entity renderer and does not issue
one draw submission per source face or prop placement.

The standard NeoForge named block-render-type path cannot bind an arbitrary
mod-owned texture in a chunk layer, and Sodium 0.8.13 exposes no public custom
terrain-pass registration API. src2mc therefore owns this narrow static-section
backend instead of mixing into Sodium's internal terrain pipeline. Avoiding
Sodium render-pipeline mixins is an explicit stability choice; they may only be
reconsidered if measured evidence proves the independent backend cannot meet a
required constraint.

Section meshes retain generation identity and page dependencies so reload and
texture residency can invalidate only affected buffers. Static world frustum
culling is shared conceptually with Minecraft but the buffers, draw submission,
and rebuild lifecycle are src2mc-owned. Moving Create contraptions require a
separate adapter because they are no longer static world sections.

Generic surface and derived carrier blocks expose no vanilla-rendered geometry.
They may supply persistence, lookup, or collision, but the src2mc section mesh
is the sole visual surface. This prevents a normal converted map from drawing a
coplanar vanilla face beneath the Source-derived face. Deliberately placing an
ordinary block face exactly coplanar with an src2mc face is outside the no-edit
paste workflow and may z-fight; Source geometry is not shifted to hide it.

## D17 — Surfaces are exact geometry owned by cells

The map's visible surfaces are the polygons the Source compiler wrote — the BSP
face lump for brushes and the displacement triangles for terrain — cut along
block planes into one convex fragment per cell, and never snapped to the grid.
Snapping to half-block micro-patches looked right in large rooms and fell apart
in tight ones: a hallway 96 units high lost a quarter of its height to rounding,
and a face drawn in one place with collision in another put ceilings a block
below where the player collided with them.

Blocks stay the editable substrate. Every fragment belongs to the block behind
it — its own cell, or one cell further behind when the face sits just off the
grid in front of a block — and is drawn only while that `src2mc:surface` block
stands. Breaking the block removes exactly the geometry it owned; placing it
back restores it. The server reports surface-block changes per section to
clients, which rebuild only the affected regions. A fragment with no block
behind it (thin brushes, slivers too thin to voxelize) is drawn unconditionally.

Props are placed at their exact Source origin. Settling them onto the voxel
floor and nudging them out of voxel walls only undid the grid's rounding while
surfaces were snapped to it; against exact surfaces the same corrections moved
props off the geometry they really stand on, so mod export no longer applies
them.

Smooth vertex light takes a cell only if the line from just in front of the
vertex to the cell's centre crosses no surface front to back. Solid blocks were
the only thing stopping the old sampler; with exact surfaces a vertex where a
floor meets an off-grid or thin wall reached into the air behind the wall, and
the other side's light bled along every such edge. When every weighted cell is
dropped — an exact floor can lie most of a block below the top of its own
block, so the point sampled half a block out is still inside it — the vertex
takes the visible open cells around it regardless of weight, then the nearest
visible open cell, never the old brightest-neighbour fallback: that one read
daylight through the block and was the actual glow along the foot of the walls
(measured with `/src2mc_light_probe`). `/src2mc_light_occlusion off` restores
the old sampler for comparison.

Brushes thinner than the brush-mesh cut-off are no longer exported as props of
their own: their faces arrive as unowned fragments like any other, and their
cells remain in the light-occlusion mask.

Collision came from the voxel grid, one full block per solid cell, until D18.

## D18 — Map collision is the exact solid volume of each cell

A map block collides as the solid volume inside its cell, not as a full cube.
The converter computes it from the converted brushes (exact planes) and the
displacement terrain (a height field one sixteenth thick), rounds it outward to sixteenths of a block and stores it per cell in the
collision table (format section 14). Outward, because a sixteenth of slack is
invisible and a floor you fall through is not; separate pieces stay separate
boxes, so gaps between bars stay open (D15).

Collision follows the same ownership as the surfaces (D17):

- A block's own volume is its shape. A block the voxelizer rounded into a room
  collides as the little of it that is really there. Source terrain has no
  thickness, so the blocks the grid backs it with collide as nothing: backing
  them put collision under an upstairs terrain floor into the ceiling of the
  room below.
- Volume in a cell with no block hangs off the face-adjacent block it touches
  most, as a shape reaching into that cell. A floor that sits 8 units up into
  the air above its block is stood on where it is drawn, and breaking the block
  takes both away together. Only face neighbours carry pieces: Minecraft asks a
  block for a shape reaching past its cell only where the entity's box touches
  that cell's face.
- Volume that touches no block, and every thin brush — drawn whatever blocks
  surround it — is carried by a `src2mc:carrier` in its own cell. Carriers only
  collide: no light, no face culling, and replaceable like tall grass so
  building into the cell simply takes its place.

A map block is aimed at and outlined as its collision. One with no collision
keeps a full-cube outline only if it owns a drawn fragment, so it can still be
broken to remove that surface; one that owns nothing is outlined as nothing
and building into its cell replaces it, so no invisible, unreachable block
ever stands in the way. The block itself stays, because the light around it
was built with it there.

Both blocks use a dynamic shape, and their light and occlusion stay fixed (the
surface block a solid cube, the carrier nothing), so the sky-light bake and the
exact-surface renderer are unaffected. Shapes are looked up per world position
from an immutable per-generation table; nothing is cached per block state, and
breaking or placing a block needs no invalidation. `/src2mc collision
exact|full` switches back to full cubes for comparison, and `/src2mc collision
probe` prints the shape of the block looked at and the cells stood in and on.

Props join the same table (first version, 2026-09-27). Source's own `solid`
setting decides: not solid, solid as the bounding box, or solid as the physics
model. `props.collision_min_size` can leave small props walk-through; it is 0
by default since DEV-0.26.0 (was 48 units; user: small things should be there
and solid as in Source). The
model's physics hull (`.phy`) is not read yet, so a physics prop collides as
the shell of its drawn mesh, a sixteenth thick. A prop's piece in a map
block's cell joins that block's shape, and one in an empty cell gets a carrier,
or the prop root when a root lands there; roots carry their cell's shape.
Removing a root does not yet remove its prop's collision elsewhere.

## D19 — Map sound plays through Minecraft's sound engine, by Source's rules

The map's sound is exported per map (format section 15) and played by the mod
through Minecraft's own sound engine, not a separate OpenAL context. That
keeps the volume sliders, subtitles, mods that mute sounds, and Sound Physics
Remastered's reverb and occlusion working on it with no integration code,
which is why Source's DSP presets are not reproduced: they are a custom effect
chain OpenAL's reverb can only approximate, and Sound Physics does the room
acoustics from the actual blocks.

- **Encoding.** WAVs (PCM, Microsoft and IMA ADPCM) are re-encoded as Ogg
  Vorbis at quality 7, which the user judged indistinguishable from the
  source; Minecraft decodes Ogg natively. A stereo file Source places as a
  point is mixed down to mono, since OpenAL cannot position stereo; other
  stereo stays stereo and plays unpanned with distance falloff, as in Source.
- **Playback.** The engine's buffer library only reads resource packs, so the
  mod decodes a bundle sound itself and puts the buffer into the engine's own
  buffer cache, reached by reflection by field type, under a content-addressed
  name in a namespace of its own. Static buffers keep sounds in the large
  static channel pool; only a loop whose loop point is past its first frame
  is streamed, from decoded samples, so its intro plays once.
- **Falloff** is Source's, applied by the mod each tick with Minecraft's own
  attenuation off: inverse distance from the sound level against a 60 dB,
  36-unit reference, full volume close by, and below 1% a linear fade to zero
  over the same distance again. The reference values are the public SDK's;
  the curve past them is the engine's and is the one part not taken from
  public code.
- **Soundscapes** follow `soundscape_system.cpp` and `c_soundscape.cpp`:
  candidate entities whose PVS holds the listener, in radius and with a clear
  line (Minecraft's block collision here, where Source traces brushes only),
  the nearest winning and the last winner staying; three-second crossfades
  that reuse a slot already playing the same wave; random sounds on their
  timers, at their positions or on a circle 36 units around the listener.
- **Surfaces.** Map blocks report their own sound events. The client
  replaces each with the surface property's soundscript, found from the
  material under the feet (or the block's largest face) in the surface table:
  steps alternate left and right at Source's 0.2 walking and 0.5 running
  volume, with sprinting as running, times a gain (default 2; Source's levels
  sounded too quiet next to Minecraft's, user 2026-10-02); a hit plays
  `impactsoft`, a break `break` or `impacthard`. A hit or break that cannot be
  placed keeps stone's sound, which `sounds.json` also plays on a client
  without the bundle.
- **Movement inside a map is Source's only** (user, 2026-10-02: Minecraft's
  sounds "just ruin it"). Every step Minecraft plays inside a placed map with
  sound, on any block, becomes the surface's step, or Source's `default`
  surface where no map face is underfoot; Minecraft's landing and fall-damage
  sounds are dropped. The local player's jump plays a full-volume step
  (`CheckJumpButton`), and a landing after a fall of 76.5 units a 0.85 step,
  after 231 units a full step and `Player.FallDamage` (`CheckFalling`, HL2's
  values; Source states them as speeds under its own gravity, so the fall
  heights are what carries over). INFRA has no `Player.FallDamage` but its own
  `Player.FallLight` and `Player.FallMedium`, whose triggers live in its
  closed player code; by the user's choice (2026-10-02) light plays on a rough
  landing and medium on a hurting one. Step gain 2 confirmed by the user.
- **Scope.** Every soundscape entity and `ambient_generic` is exported with
  its entity index and flags. Without the map's logic running, only what
  Source starts at spawn plays; with it, the logic decides (D20).
  Standing on a prop plays its model's `$surfaceprop` when the prop's box
  (the mesh bounds, turned and scaled as placed) tops the map floor there by
  more than a sixteenth; flatter props sound like the floor, since the bundle
  does not say which props are solid.

## D20 — The map's logic runs on the server, by Source's rules

Almost everything a map does after it loads hangs on Source's entity I/O:
triggered sounds, voice lines, buttons, doors, alarms, music. The survey of the
ten test maps (2026-10-02) counted 9,359 output connections; on the INFRA maps
the logic layer alone (triggers, buttons, relays, timers) reaches 85-98% of
the `ambient_generic`, scene and music inputs, so it was built first, before
anything that moves.

- **Data.** The converter exports every entity of the lump with every
  keyvalue and every output as written (format section 16), not a subset, so
  later work (VScript, lights, movers) reads the map's own data. Brush
  entities carry their brushes as plane sets; scenes are parsed from INFRA's
  loose `.vcd` text, captions from `closecaption_english.txt` and
  `subtitles_english.txt`.
- **Where it runs.** On the server, one `MapLogic` per placement, so
  multiplayer and saving work like any server state. The rules follow the
  Source SDK 2013 code line by line where a chain can tell the difference:
  the event queue fires in time order and in insertion order at equal times;
  an output fires its connections in the reverse of the order the map lists
  them (`AddEventAction` prepends); a connection's own parameter skips the
  output's extra delay; a target name is looked up when the event fires, then
  as a classname when no name matches; `!self`, `!caller`, `!activator`,
  `!player` and trailing `*` resolve as in `FindEntityProcedural`. The logic
  entities, triggers, buttons and doors keep their SDK state machines; a
  button or door move is its duration (`speed`, `distance`, `lip`, the brush's
  size), so outputs waiting for "fully open" wait as long as in Source while
  nothing moves yet.
- **When it runs** (user, 2026-10-02). A map starts only by command
  (`/src2mc logic start`, usable from a command block) or by a level change
  into it. Its clock advances only while a player is inside it and the
  world's tick rate is not frozen: Source does not run a level nobody is in.
  At about a millisecond per simulated second on the largest test maps, it
  runs on the server thread; no worker thread is needed.
- **Multiplayer** (user, 2026-10-02: "closest to Source"). `trigger_once`
  fires for the first player; `!player` is the player who set the chain off,
  else the first player inside; scenes, captions and sounds go to every
  player in the dimension. Logic ignores broken blocks; a button whose blocks
  are gone simply cannot be aimed at. A spectator is Minecraft's noclip, and
  touches triggers as Source's noclipping player does; only a level change
  ignores it, as `TouchChangeLevel` does (found 2026-10-03: a monologue was
  missed by passing its trigger in spectator).
- **Level changes** (user, 2026-10-02). A `trigger_changelevel` moves the
  player to the nearest placement of the next map, keeping their position
  relative to the shared `info_landmark`; that map starts as a transition
  (`OnMapTransition`), and an empty running one is activated again, as a
  Source level restore does. Unplaced, it only says so. `/src2mc place_chain`
  places a map and its successors side by side. A player arriving inside a
  volume does not touch it until they leave it once, so the reverse
  changelevel does not bounce them back.
- **Sound.** The server sends each sound entity's state (on, a serial that
  changes per start, level, pitch) when it changes and a full copy to a player
  who arrives; one-shots (button clicks, door noises, scene lines) are sent
  as events. The clients play them through the D19 sound player. Captions
  draw in Source's style above the hotbar.
  Map music plays in Minecraft's Music category, and inside a placed map
  Minecraft's own music never plays (user, 2026-10-03: "fully get rid of
  minecrafts music"), through NeoForge's `SelectMusicEvent`.
- **Out of scope for now, designed for.** VScript (`RunScriptCode` and
  `logic_script` are counted, not run), lights, moving brushes and props
  (phase B), and INFRA's camera, documents and corruption (with VScript).
  Every input an entity does not handle is counted per class and input in
  `/src2mc logic status`, which shows what the next phase must cover.


## D21 — Moving entities are Sable sub-levels

Doors, buttons, lifts, trains and `func_brush` move in Source; a block world
cannot move a block. Every map mover is therefore its own small map: the
converter leaves it out of the world (blocks, surfaces, collision, props) and
writes its exact surfaces, collision and the props parented to it in
mover-local cells (format section 17). The mod builds one Sable sub-level per
mover and placement, of unbreakable invisible `src2mc:mover` blocks, and
Sable carries it (user, 2026-10-03: "use Sable for any movement as this
doesn't mean we have to do two systems"; Sable is a required dependency).

- **One system for all motion.** Sable already makes players and mobs collide
  with a rotated or moving block structure, carries the players standing on it
  (lifts, trains) and pushes those in its way, and networks and interpolates
  its pose. src2mc only says where the sub-level is.
- **Kinematic, not simulated.** A sub-level is a physics body, and gravity
  pulls it. A resting mover is held by a fixed constraint to the world at its
  pose, as Create: Aeronautics' physics staff freezes a sub-level (checked in
  its `PhysicsStaffServerHandler`). A moving one is teleported on every physics
  substep to the pose its entity has at that moment, its velocity cleared,
  and held there by a new constraint, so it follows Source's `LinearMove` and
  `AngularMove` exactly. A move that reverses part way takes the remaining
  distance at the entity's speed, as Source's does.
- **Collision.** Entities collide with a mover through the block's shape at
  its plot position, which is the mover's own collision table (D18), found
  through the plot's centre block, where mover-local cell (0, 0, 0) stands.
  Sable's physics engine is given no shape for these blocks: contacts with the
  door frame a door slides past would only fight the kinematic path. Sable's
  per-state mass and solidity checks see a full cube, since a sub-level
  without mass is removed.
- **Drawing.** The plot's blocks are invisible. src2mc bakes each mover's
  surfaces and carried props into mover-local buffers and draws them with the
  sub-level's interpolated render pose. Each vertex is lit by the world where
  the mover is now (sampled as static surfaces are), again once it has moved
  a quarter block or turned 5 degrees, at most every 6 frames. Normals stay
  mover-local; the draw turns vanilla's two shading lights back by the
  mover's rotation instead, which shades the same as turning every normal
  and needs no rebuild while a fan spins (DEV-0.25.0).
- **Hierarchy.** A child's pose is its own move followed by its parent's,
  through any chain of `parentname`s, including parents that do not move
  themselves (a gate parented to a prop that rides a lift). A train's path
  and a parented train's nodes are in the parent's compiled frame, as Source
  requires them to be.
- **State.** The logic gives each mover two bits, hidden and not solid
  (`func_brush` Enable/Disable and `solidity`, non-solid rotators, killed
  entities), synced to the clients; hidden movers are not drawn, non-solid
  ones collide as nothing. A `passable` train stays solid: in Source it is
  walked on through the physics models of the props riding it, which a
  mover's props do not have here, so its brushes stand in (user: the carts
  lost their collision in DEV-0.25.0).
- **Riding without moving.** Triggers and use volumes parented to a mover
  are tested where the mover took them: a player's box goes into the
  trigger's compiled frame through its parents' poses, and the use ray
  through the nearest mover it is parented to. Small props are exported at
  any size: the old 12-unit floor dropped a train's buttons and switches, and
  the user asked for it gone everywhere (DEV-0.26.0).
- **Trains and rotators.** `func_tracktrain` follows its `path_track`s as
  `CFuncTrackTrain::Next` does, one logic tick at a time, firing `OnPass` at
  each node and at the dead end; between ticks its pose is the same walk
  along the path, so Sable's substeps follow the path exactly.
  `func_rotating` keeps its speed between its own speed steps. A train turns
  relative to its spawn angles: Source turns a brush model by its absolute
  angles, but every train with spawn angles on the test maps was built
  facing along its track already (awaiting the user's in-game comparison).
- **Lifetime.** Sub-levels exist for every placed map, whether its logic runs
  or not; Sable saves and loads them with the world, and the dimension's saved
  data keeps which is which. A removed placement or a re-export that changes a
  mover replaces its sub-level. `/src2mc movers status|respawn`.
- **Not yet.** Skeletal animation (`SetAnimation`), `env_sprite`, lights and
  VScript come later (user, 2026-10-03). Movers cannot be broken for now.

## D22 — Props the logic changes are drawn and collide on their own

A map's I/O switches a lamp's `Skin`, `Kill`s a key a button picks up and
`Enable`s a broken chain in place of a whole one (B2; furnace: 122 `Skin`,
11 `Kill`, 6 `Enable`). Every other prop is merged into its section's
aggregate and its collision into the map's cells, so the converter marks the
props some input changes (format section 18) and the mod keeps those apart.

- **Which props.** Every model entity some output sends a look or solidity
  input, resolved as Source resolves targets, plus dynamic props that start
  disabled; up to 150 per test map. Removing an entity removes its children
  (`UpdateOnRemove`), so props parented to a killed entity count too, and the
  mod's `Kill` now removes children as well.
- **Drawing.** Each is built alone, in the skin and tint its state names, and
  built again when that changes, so no change rebuilds a section aggregate.
  Props riding a mover rebuild their mover's buffers instead. A hidden prop is
  not drawn; one past the render distance is not built.
- **Collision.** A prop that can be removed or switched to not solid keeps its
  volume in a collision table of its own, which the mod adds to the cell's
  shape while the prop is solid (user, 2026-10-03: "A" — remove the collision
  with the prop rather than leaving an invisible box). Cells without a map
  block get carriers that collide as nothing while it is gone. A riding prop's
  collision stays merged into its mover's.
- **Source's rules.** `Enable`/`Disable`/`TurnOn`/`TurnOff` only toggle
  `EF_NODRAW` on `CDynamicProp`; it stays solid. `Enable`/`DisableCollision`
  toggle `FSOLID_NOT_SOLID`. `Skin` is `atoi`, `Color` and `rendercolor`
  `UTIL_StringToColor32`.
- **Tint everywhere.** `rendercolor` tints every prop, not only logic props
  (user, 2026-10-03: option A): entity props by the keyvalue, static props by
  the lump's diffuse modulation, read for versions 7 to 9 only, the layout the
  test maps were checked against (Portal 2's gel tubes are orange and blue).
  The tint is part of the model reference, so placement records are unchanged.
- **Not yet.** `Alpha`/`renderamt`, and `trigger_remove` taking a carried
  prop. Skeletal animation came with D24.

## D23 — Texts, fades and shakes follow Source's client code

`game_text`, `env_fade` and `env_shake` (B3) are user messages in Source: the
server decides who gets what, the client draws it. The mod does the same:
the server entities follow `maprules.cpp`, `EnvFade.cpp` and `EnvShake.cpp`
with `UTIL_HudMessage`, `UTIL_ScreenFade` and `UTIL_ScreenShake` ("every
player" being every player inside the map), and the client follows
`CHudMessage` and `CViewEffects` line by line.

- **Text.** Six channels, a new text replacing its channel's; positions as
  screen fractions with -1 centring; the fade (0), flicker (1) and scan-out (2)
  effects with Source's per-character colour blend, including drawing the
  characters not yet scanned black. The font is Minecraft's, bold, at
  Trebuchet 24's 24 screen pixels. The colour's own alpha is ignored, as the
  2013 client ignores it. `#` tokens are localized by the converter (format
  section 16 `strings`).
- **INFRA chapter titles.** INFRA's engine creates the two title texts and runs
  `chapter_titles.nut`; neither is in the map. The converter writes both as
  engine entities and the script's `EntFire` calls as engine events, so the
  mod runs no VScript. The texts' fade and scan timings are Portal 2's own
  entities of the same names, which INFRA's engine branch shares (its
  `server.dll` holds the same message and colour strings); awaiting the
  user's comparison with INFRA.
- **Fade.** Several fades sum their colours and take the highest alpha;
  modulate multiplies the screen. Durations go through Source's 7.9 fixed
  point, so a 99999-second hold is about 128 seconds, as in Source. A fade
  covers the HUD unless it has Portal 2's flag 16.
- **Shake.** A random offset (up to 16 units, half a block) and roll, re-rolled
  at the frequency and settling over the duration, amplitude falling off with
  distance to the radius; players in the air are left alone unless the flag
  says otherwise. The roll goes in through the camera angle event; the offset
  needs `Camera.setPosition`, made public by an access transformer and applied
  in the field-of-view event, the one fired after the camera is placed and
  before the world is drawn. Physics and rope shaking are not done. Shakes
  run on the map's clock, sent with each shake: Source settles a shake as
  `sin(curtime * freq)` with `curtime` the map's time, so the world's game
  time made them move at the wrong speed (user, DEV-0.28.0: "way too slow").
- **Clearing.** Starting or stopping a map's logic takes its texts, fades and
  shakes off the screens of the players inside, as loading a level clears
  Source's view effects; `/src2mc_screen clear` does it by hand.
- **Not yet.** `env_fade`'s `FadeReverse` (Portal 2, not used by the test
  maps), `env_hudhint`, `env_instructor_hint`, `point_clientcommand`.

## D24 — Props play their sequences as Source's bone setup and CDynamicProp do

A `prop_dynamic` the map tells to `SetAnimation` — a button pressing in, a
lever, a door sliding open, a clock, a windmill — moves its bones along one of
its model's sequences; 304 such props across the ten test maps (furnace 52,
metro 85). The converter writes each animated model's skeleton and sequences
(format section 19) next to its mesh, the server plays them by
`CDynamicProp`'s rules, and the client poses the mesh.

- **Reading.** `vmdl` panics on animation kept in an `.ani` file, reads the
  value streams unsigned and stops at frame 255, so the converter reads the
  skeleton and animation itself, line by line after the 2013 SDK's
  `bone_setup.cpp` (`CalcAnimation`, `CalcBoneQuaternion`, `CalcBonePosition`,
  `ExtractAnimValue`) and `studio.cpp` (`pAnim`, sections, animation blocks).
  Every animated model of the test maps reads. Its own bone weights come from
  the `.vvd` as stored (`vmdl` divides them by the bone count).
- **Which sequences.** All keep their name and timing, so `LookupSequence`
  (label, then activity with a weighted pick) finds what Source would; only
  those the map can reach carry frames: sequence 0, every `SetAnimation` and
  `SetDefaultAnimation` parameter and `DefaultAnim`, the idle ones of a random
  animator, and a transition graph's sequences. escape_02 adds 13 MB
  uncompressed (8 MB in the bundle), furnace 0.2 MB.
- **Server.** `DynamicProp` follows `props.cpp`: `PropSetAnim`,
  `PropSetSequence` through `GotoSequence`'s transition graph,
  `FinishSetSequence`, `ResetSequence`, `AnimThink` every 0.1 s with
  `StudioFrameAdvance` (0.2 s at most per advance), `OnAnimationBegun`,
  `OnAnimationDone` on the first think after the cycle reaches its end, then
  the `DefaultAnim` again, and the random animator's idle picks (with
  Source's doubled first think time). `SetPlaybackRate` and `SetCycle` are
  `CBaseAnimating`'s. A one-frame sequence never finishes, as in Source.
- **Clients.** The state carries the sequence, the cycle at a map time and the
  rate, sent when Source would make the cycle jump, not every think; each
  client advances it on its own clock, synced to the map's with every state,
  as Source's client interpolates the cycle. A sequence change fades the old
  one out over `min(old fade-out, new fade-in)` with `C_AnimationLayer`'s
  spline (`CSequenceTransitioner`), unless the new one snaps.
- **Drawing.** A triangle whose three corners follow one bone alone is drawn
  in that bone's buffer with the bone's transform, so posing costs a matrix;
  only triangles several bones pull are skinned on the CPU, by their corners
  and each point by its corners as the studio renderer does, and uploaded
  again when the pose changes. Normals stay model-space and the shading
  lights turn back per buffer, as for movers. `/src2mc_anim status|draw`.
- **Still dynamic props** are exported in sequence 0's first frame, which
  `CDynamicProp` keeps when nothing animates it, rather than the reference
  pose (user, 2026-10-04: yes); 7 models of the test maps differ, up to 136
  units.
- **Collision follows the pose** of every animated prop: the converter writes
  the collision of each pose a reachable sequence leaves the prop in (its last
  frame, or its first for one that loops), and the mod switches to it once the
  sequence has played; a door blocks until open and frees once shut. Source
  only moves the collision of a model with several physics solids (bone
  followers); single-solid doors such as furnace's `cellardoor1` are opened
  there by an invisible clip brush. Props collide here as their drawn mesh
  and clip brushes are left out, so following the pose is what reproduces
  that (user, 2026-10-04: all animated props, "but later i would like to
  switch to use .phy full for collision"). Revisit with `.phy` collision.
  A prop riding a mover keeps its own collision in the mover's cells the same
  way (logic props version 3), so testchmb_a_00's elevator doors stop
  blocking once open; it also frees a riding prop the logic hides or makes
  not solid, which stayed merged in its mover before.
- **Relighting a moving mover** sends only new light bytes: the buffer's
  bytes are kept after the first upload, a worker writes each vertex's light
  into them and the render thread hands them to `glBufferSubData`. Building
  the whole buffer again on the render thread cost sp_a2_bts4's conveyors
  (four movers with hundreds of thousands of vertices of debris and belt
  modules) a spike of up to 96 ms every few frames.
- **Not done.** Include models (none in the test maps), IK rules and locks,
  procedural bones (metro's `switch_010_key_anim`), local hierarchy, auto
  layers, animation events (sounds and effects a sequence triggers), pose
  parameters other than at spawn, bone-attached children (`parentname`
  with an attachment rides the prop's origin).

## D25 — point_template copies entities at runtime, as Source does

sp_a2_bts4's conveyors loop through `point_template` `ForceSpawn`: each belt
train that passes a node near the start makes a new train and belt module at
the start, a timer drops a random piece of turret debris at a fixed spot, a
`trigger_once` that lets everything through (spawnflag 64) parents it to the
train touching it (`SetParent !activator`), and the end node kills the train
and what rides it. Without templates the conveyors ran once and stopped (user,
2026-10-04: "they should repeat and never stop"; chose the full behaviour,
debris included).

- **Templates** follow `MapEntity_ParseAllEntities`, `point_template.cpp` and
  `templateentities.cpp`: each template in lump order takes what its
  `TemplateNN` name out of the map before anything spawns (spawnflag 1 keeps
  them; a later template cannot find what an earlier one took), and
  `ForceSpawn` makes copies at the compiled places (templates here do not
  move), spawns them, links their parents, activates them and fires
  `OnEntitySpawned`. Name fixup (`Templates_ReconnectIOForGroup`, not with
  spawnflag 2) appends `&NNNN` to a keyvalue up to its first comma or an
  output target that names a group member, and to that member's name; the
  instance counter starts after one count per template, as precaching does.
  Output targets are compared on their own, which also covers Portal 2's
  ESC-separated outputs.
- **Runtime entities.** A copy takes a slot after the lump's
  (`LogicEntity.source` is the record it was made from); a removed copy's
  slot goes to a copy made a second or more later, so a conveyor running for
  hours does not grow the entity list. Taken at once, the end node's kill and
  the start's new train shared a slot: the sub-level jumped from the end to
  the start, solid, carrying the player, and slots taken by another kind of
  entity moved stale sub-levels with it (user test, 2026-10-04). Copies are saved by slot, record, template and instance
  and made again on load.
- **Parenting** is a rigid move: world = parent . hold . own. The map's links
  hold nothing; `SetParent`/`ClearParent` and a copy's `parentname` at spawn
  keep the entity's world place. Removing a parent removes its children.
- **Movers.** A sub-level whose entity is gone is let go at once (entity -1,
  hidden and passable, left where it is) and handed to a new entity of the
  same bundle mover a second later at the earliest, once whoever stood on it
  has fallen off; else a copy gets a new one. Handed over, it stays hidden and
  passable 3 ticks while Sable carries it to its new place. A copy's sub-level draws no riders baked in; they are copies too.
- **Mounted props.** A copy of a logic prop, or one re-parented at runtime,
  is sent to the clients as a mount (its record, the mover entity it rides
  or none, its move relative to that mover's) and drawn by the animated prop
  renderer, still models as one rigid bone, with tessellated geometry shared
  per model and tint, cut on a worker the first time. Their collision is not
  done yet. A copy or a prop riding a mover is lit on a worker against a
  copy of the world, and relit by sending only its light bytes, as movers
  are: lighting debris on the render thread cost up to 80 ms and cutting it
  up to 47 ms (user test, 2026-10-04). Hidden movers are not relit. A prop
  on a hidden sub-level is not lit, and one that moved 2 blocks since it was
  lit is lit again: belt modules lit on a reused sub-level before Sable had
  carried it from the belt's end kept that light along the belt, every few
  modules glowing (user test, 2026-10-04).
- **Triggers** are touched by solid movers as well as players, by the
  mover's moved brush box, when the trigger lets everything through
  (`FinishPushers` touches triggers for every pusher). Solid props riding a
  mover (`PhysicsRelinkChildren`) do not touch them yet.
- **Invisible mover brushes collide.** The conveyor trains are a 2-unit
  `tools/toolsplayerclip` plate with solid contents under a belt prop that
  does not collide (`solid 0`); the converter dropped brushes with no drawn
  side, so the belts had no collision (user, 2026-10-04: "makes this map
  unplayable"). A mover now collides with such brushes when it is exported
  anyway; an entity of nothing else stays no mover, or INFRA's invisible
  button volumes would be 50 to 100 more sub-levels per map. World brushes of
  that kind (28 to 125 per test map) collide too (user, 2026-10-04: "whats
  closest to source engine"): solid contents or player clips, every side
  undrawn, cut to the cells the rest of the map fills, so clips far in the
  void do not widen a map (sp_a1_wakeup would have grown by 161 blocks). Map
  bounds are unchanged on all ten test maps; collision tables grew 1 to 7 %.
  Monster clips stop only NPCs and stay out; brush entities other than movers
  (triggers are invisible and solid too) are not affected.
- **Slots handed out twice.** Freed slots were kept in a TreeMap and taken by
  removing the entry being iterated; TreeMap reuses a deleted node for its
  successor, so the slot returned was not the one removed and a slot could go
  to two entities. On sp_a2_bts4 that put trains, belt modules and triggers in
  one slot after about a minute: debris and belts vanished, sub-levels followed
  the wrong entity, and both conveyors died within two minutes (user test,
  2026-10-04). A 5-minute simulation now holds about 17 and 22 trains with
  every module and debris on them.
- **Not done.** A moving point_template, copies of usable entities (their
  use boxes are the client's lump records), ambient sounds in templates,
  `SetParentAttachment`, and collision of mounted props.

