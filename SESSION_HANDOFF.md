# src2mc implementation handoff

Updated: 2026-10-03 (Europe/Berlin), DEV-0.26.0 (fixes from the DEV-0.25.0 in-game test)

## NEXT TASK: map logic phase B, continued (B2 next)

User test of DEV-0.25.0 (2026-10-03): prop doors, func_brush, elevator, fans, lighting and
the old doors all fine. Trains moved and faced right, but: no wheel spin (the checklist fired
`StartForward` on the train directly, which skips the relays that start the wheels; the map's
relays spin them at 800 deg/s, checked in a simulation), carts lost collision (passable flag;
now solid again), the second minitrain's buttons stayed behind (their button and switch
models were under the converter's 12-unit size floor and never rode; use volumes and triggers
parented to movers did not move). The second minitrain jumping forward on logic start is
Source's `Find` (teleports to its first node, `path_5`, 6 blocks ahead of where it was
compiled); ask the user to compare with INFRA if it still looks wrong.
DEV-0.26.0: triggers, use aim and the `/src2mc_logic_show` wireframes follow the mover
carrying them (client helper `Carriers`); passable trains solid. User test of the first
DEV-0.26.0 build: wheels spin, collision back, buttons work, train 2's jump confirmed as
INFRA's own behaviour (user checked the real game). User then asked to drop the prop size
floor altogether ("various missing buttons, levers, wheels, rocks"): `[props] min_size` now
defaults to 0 (was 12 units). All 10 bundles regenerated and installed. User confirmed the fixes
in game, then asked to drop the collision floor too: `collision_min_size` now defaults to 0
(was 48), so props are solid by their Source `solid` setting at any size. Bundles
regenerated and installed again. User: all fixes work, performance not noticeably impacted
(2026-10-03); committed as DEV-0.26.0.

Then, in the agreed order:

6. B2: `prop_dynamic` Skin, Enable/Disable, color (biggest unhandled inputs; furnace: 122
   Skin, 11 Kill, 6 Enable, 1 Disable). Needs the converter: static props carry no entity
   index, and only skins some prop wears are exported as model references. Plan: tag
   props.s2props records (or a side table) with their entity index for named
   prop_dynamics, export every skin family's material array for those models, then the
   mod rebuilds the prop's section aggregate (or the mover's buffers) on a change.
7. B3: INFRA chapter titles (`game_text`), `env_fade`, `env_shake`.
Later (user): skeletal animation (test case: furnace `cellardoor1` #2209, a prop_dynamic
slid by `SetAnimation open/close`; its `func_door` #2112 is an invisible clip, not exported),
`env_sprite`, lights, VScript, INFRA gameplay. Each step ends with the user's in-game test.
Also open: train/rotator sounds (MoveSound, StartSound, rotator `message` loop) are not
played; `MoveToPathNode`/`TeleportToPathNode`/`LockOrientation` (INFRA FGD extras) unhandled;
props riding a hidden func_brush hide with it (Source would still draw them); triggers are
touched by players only, so INFRA's cart bumpers (`car_multiple*`, "everything" flag, touched
by the other carts' props in Source) never fire from cart contact.

## DONE: map logic phase B steps 1-5 (DEV-0.25.0/0.26.0, user-confirmed 2026-10-03)

- Prop doors on the use key: `PropUseBox` (model box turned and placed as compiled) for
  `UseInput` aim and `LogicSystem.use` reach; `SF_DOOR_IGNORE_USE` (32768) honoured.
- Mover state bits HIDDEN / NOT_SOLID (`MoverRegistry`), from `LogicEntity.moverState()`
  each server tick, synced by `MoverNetwork.StatePayload` (protocol "5"); renderer skips
  hidden, collision empty when not solid. `Movers.Brush` = CFuncBrush (Enable/Disable/
  Toggle, StartDisabled, solidity). Furnace test: `ap_prehall_brush` #532 hides while the
  player stands in trigger #1869.
- `Trains.PathTrack` (Link, LookAhead, InPass/OnPass, Enable/DisablePath, alternate),
  `Trains.TrackTrain` (Find, Next per tick, ArriveAtNode, DeadEnd, teleport flag, all speed
  inputs, velocity/orientation types), `Trains.Rotating` (CFuncRotating incl. Acc/Dcc steps
  and StopAtStartPos). Passable trains and flag-64 rotators are NOT_SOLID, as in Source.
- Hierarchy: `LogicEntity.worldPose` composes parent poses (`MoverPose.compose`); furnace
  cases: minitrain wheels, `elevator_gate1` #1398 via prop `elevator` #1519 on
  `elevator_tractrain`, `ladle_ch_hooks` on `lc_gantry`, func_brush #37 on prop door #2071.
- Train orientation is relative to spawn angles (see D21; the 4 furnace trains with angles
  `0 -90 0` are built along their tracks). User to compare against INFRA.
- MoverRenderer: per-vertex smooth light, relit after 0.25 block / 5 degrees (max every 6
  frames) or a middle-light change; shading lights counter-rotated per mover.
  `/src2mc_movers status|draw on|off|light vertex|single|shading turned|unturned`.
- Tests: train path/OnPass/dead end, train save mid-path, rotator speed and friction, child
  on moving parent, func_brush states; realMapsRun now starts every train and rotator and
  checks every mover pose stays finite (6 maps clean).

## DONE: map logic phase B1 (DEV-0.24.0, user-confirmed 2026-10-03)

User: "Everything looks good and the sliding door works correctly", then all doors fixed.
Decisions (user, 2026-10-03): all movement through Sable (hard dependency, 2.0.3 in the dev
client, compileOnly from maven.ryanhcode.dev); movers not breakable; animation, env_sprite,
lights later. Design in D21. Hold-still = fixed constraint world<->sub-level (as Create:
Aeronautics' physics staff); moving = per physics substep teleport + resetVelocity + new
constraint at the Source pose. Decompiled references (regenerate with vineflower if gone):
`target/sable-src`, `target/sable-rapier/src`, `target/aero/sim`, `target/companion`.

Converter (format section 17): mod export takes `MOVER_CLASSES` (+ prop_door_rotating) out of
the world into `maps/<id>/movers.json` + `movers/<entity>.s2faces|.s2coll`, props riding them
by parentname (`src/output/movers.rs`); empty movers (nodraw/clip only, nothing riding) are
left out. Fixed: the 3D-skybox filter tested brush-entity brushes at their entity-relative
position and dropped nearly all of them on INFRA. Furnace: 71 movers, 69 riding props.
Mod: `MoverTable`, `src2mc:mover` block, `MoverRegistry` (plot pos -> mover cell via plot
centre; resolves the level through Sable's `LevelAccelerator`), `MoverPose`, Door/Button
`pose(t)`, `MoverSystem` (spawn, lock, drive, saved data `src2mc_movers`, sync, cells joined
into one face-connected piece so Sable never splits them), `MoverNetwork` (protocol "4"),
`MoverRenderer`, aiming through the moved pose, `/src2mc movers status|respawn`.
Bugs found in the in-game test: saved data read inside Sable's container-ready event (fires in
the ServerLevel constructor, crash); Sable splitting disconnected movers (pieces fell out of
the world); prop doors rotated by their angles twice (prop is placed at its closed angles;
prop doors now move by angles per CPropDoorRotating: forward = yaw - distance, hinge swap,
spawnpos, open away from the player); `func_brush` solidity 1 collided; Sable's entity
collision asks through `LevelAccelerator`, which fell back to full cubes.
Known gaps: rotated movers keep unrotated normals; one light per mover; nodraw/clip mover
brushes have no collision; thin movers not in the light-occlusion mask; F3+B shows Sable's red
sub-level bounds (debug only).

## DONE: map logic phase A (DEV-0.21.0 to DEV-0.23.0, user-confirmed 2026-10-03)

Design and user decisions are D20 in docs/decisions.md; the data is format
section 16 (`logic.json`) and the sound table's version 2 (section 15).

- **Converter:** `src/output/logic.rs` (entity table, brush volumes, scenes,
  captions), `src/source/vcd.rs`, `src/source/captions.rs`; `EntityRecord`
  keeps ordered keyvalues (the old BTreeMap lost repeated outputs) with a
  case-insensitive, last-wins `get`. Captions come from
  `closecaption_english.txt` and then INFRA's `subtitles_english.txt` (its
  dialogue lives there). Sound table v2: every soundscape entity and
  `ambient_generic` with `entity`/`flags`/`start_disabled`; scripts also hold
  scene lines, `infra_music` and button/door sound keys (files as
  `lowercase/path.wav` scripts at volume 1, pitch 100, level 75).
- **Mod, server:** `logic/` -- `MapLogic` (clock, queue, lookups, touches,
  saving), `LogicEntities` (relay, auto, branch, listener, case, compare,
  counter, timer, filters, instance proxy), `Triggers` (once, multiple,
  changelevel), `Movers` (buttons, momentary buttons, doors, `infra_button`,
  prop doors: states and durations only), `SoundEntities` (ambient_generic,
  env_soundscape, infra_music, scenes), `LogicSystem` (start/stop, freeze,
  sync, use, level changes), `LogicSavedData`, `LogicCommands`,
  `LogicNetwork` (protocol "3").
- **Mod, client:** `client/logic/` (`ClientLogic` state copy, `UseInput`
  raycast of the use key against usable volumes, `CaptionOverlay`);
  `client/audio/LogicSounds` (one-shots, voice lines, music), `AmbientPlayer`
  and `SoundscapePlayer` follow the server state when a map runs.
- **Commands:** `/src2mc logic start|stop|status [map]`, `list <filter>`,
  `trace on|off`, `fire <target> <input> [param]`; `/src2mc place_chain <map>
  <count>`; `/src2mc_audio captions on|off`.
- **Tested:** 571 Rust tests; mod suite incl. `LogicRuntimeTest` (queue
  order, reverse connection order, times, relay refire, CancelPending,
  branch listener, case, counter limits, timers, save/load mid-delay); all 10
  bundles validate; a headless run of each real map (every usable button
  pressed, 130 simulated seconds) throws nothing and costs 0.2-1 ms per
  simulated second. **Not yet seen in game.**
- **In-game test plan (furnace; chain tunnel4 -> furnace):** place, `/src2mc
  logic start`, walk the map: triggered ambients and alarms, monologue lines
  with captions, the announcements, button clicks; `status` for unhandled
  inputs. Then `place_chain infra_c3_m4_tunnel4 2`, start tunnel4, walk to its
  level change into furnace.
- **First in-game test (2026-10-03, DEV-0.21.0):** level changes work both
  ways; button sounds and voice lines at the master switch work. Music did not
  play anywhere -- the dev client's Music slider is 0 (`options.txt`), and map
  music plays in Minecraft's Music category; awaiting a retest with it up. The
  FactoryEntered monologue (trigger_multiple #530 -> `monologue_enter`) did not
  play; headless, all 35 furnace scene lines play when their scenes start, so
  the trigger or the client is suspect -- retest with `/src2mc logic trace on`.
  DEV-0.21.1 narrows the level-change arrival guard to level changes only.
- **Third test (2026-10-03, DEV-0.23.0): user-confirmed "All fixed and
  working"** -- music (with Minecraft's music silenced inside maps), the
  FactoryEntered monologue, level changes both ways, the trigger overlay.
- **Second test (2026-10-03):** music works with the slider up; user wants
  Minecraft's music gone inside maps -- DEV-0.22.0 selects no music while the
  player is inside a placed map. The FactoryEntered monologue still did not
  play: its trigger #530 lies right behind `plant_exit_door` (a `func_door`
  whose visible sliding door is `prop_dynamic` #573 parented to it). That
  prop is a static, solid prop at the closed position here, so the doorway
  stays shut after the chain is cut. The user passed it in spectator for 1.6 s
  (log), and the runtime skipped spectators: #530 never fired. DEV-0.23.0:
  spectators touch triggers like Source's noclip (level changes excepted, as
  `TouchChangeLevel`), and `/src2mc_logic_show` draws brush-entity volumes. Doors that open in Source being shut props is the
  main phase B problem. Also unhandled: INFRA's chapter titles
  (`@chapter_title_text` game_text Display on OnMapTransition).
- **Known gaps (next phases):** nothing moves (doors, buttons, trains,
  `func_rotating`: phase B, needs per-entity geometry in the renderer and
  collision); `prop_dynamic` skin/animation, `env_sprite`, lights, particles,
  `env_shake`/`env_fade`, `point_viewcontrol` are counted as unhandled;
  prop doors cannot be used (they have no volume); VScript is counted, not
  run; entities carried over a level change (tunnel4's raft) are absent.
  Portal 1 scenes are only in the compiled `scenes.image` and are missing.
  Momentary buttons turn while use is held; INFRA's held buttons fire
  OnStart/StopPressing by guess at the FGD's meaning.

**Prop steps (DEV-0.20.0, user-confirmed 2026-10-02):** standing on a prop plays
its model's `$surfaceprop`. Validation now keeps each mesh's bounds on its
`BundleModel`; `client/audio/PropGround` boxes every prop (turned and scaled
like the renderer does) and wins over the map floor when its top is more than
a sixteenth above it. No re-export needed.

**Committed and pushed:** sound work DEV-0.17.0 to DEV-0.19.0 as c911bbf.

**User-confirmed 2026-10-02:** DEV-0.18.0 movement sounds "all sounds good";
step gain 2 is right (already the default). **DEV-0.19.0 (user-confirmed 2026-10-02, "Works"):**
INFRA's `Player.FallLight` plays on a rough landing (>= 76.5 units fallen),
`Player.FallMedium` on a hurting one (>= 231 units) when the game has no
`Player.FallDamage`; both now exported with the player scripts.

**Movement sounds (DEV-0.18.0, awaiting in-game test):** user feedback on
DEV-0.17.0: "All ambiance is there", Minecraft's walking/sprinting/falling
sounds "just ruin it", and our steps were a bit quiet. Now every `*.step`
inside a placed map with sound becomes Source's step (default surface where no
face is underfoot), `*.fall`/`*.small_fall`/`*.big_fall` are dropped, the
local player's jump and hard landings play Source steps (SDK `CheckJumpButton`,
`CheckFalling`), and `Player.FallDamage` is exported when the game defines it
(Portal and HL2 do; INFRA instead has `Player.FallLight/Medium/Fatal` and a
jump sound via operator stacks, triggered by its closed player code -- asked
the user whether to map them). Step gain default 2, `/src2mc_audio steps <g>`.

**Map sound (DEV-0.17.0, implemented 2026-10-02, awaiting in-game test):**
tiers 1 and 2 of the audio feasibility check, design in `docs/decisions.md` D19,
format in `docs/format.md` section 15 (`maps/<id>/audio.json` plus
`audio/<sha>.ogg`; a format change, hence the minor bump). Converter:
`source/{keyvalues,wav,sound}.rs`, `output/audio.rs`; `--no-audio` skips it.
WAV decode covers PCM 8/16/24/32, float, MS and IMA ADPCM; Ogg Vorbis q7 via
`vorbis_rs` (libvorbis/aoTuV, BSD-3, built from C) with a fixed stream serial,
so exports stay deterministic (furnace fingerprint identical across runs).
Mod: `client/audio/` (`SourceAudio` coordinator, `SoundLibrary` puts decoded
buffers into Minecraft's buffer cache by reflection, `SourceSound`,
`SourceFalloff`, `SoundscapePlayer`, `AmbientPlayer`, `SurfaceSounds`,
`SurfaceProbe`), `world/Src2mcSounds` (surface sound events and their
`DeferredSoundType`, returned only by the positional `getSoundType`; the plain
one stays stone for Sound Physics' reflectivity). `/src2mc_audio` status and
`soundscapes|ambient|surfaces on|off`. All 10 bundles re-exported with sound,
validated, installed (furnace: 308 sounds, 14.6 MB Ogg, 13 soundscapes, 49
soundscape entities, 4 ambient sounds; 113 ambient_generics need logic). Not
verified by ear yet: the falloff curve past Source's public reference values
(engine code, not in the SDK), step volume mapping (sprint = Source run),
whether Sound Physics processes the unpanned/everywhere sounds well, and the
SPR raycast through map blocks. Props are not probed for step sounds yet.

Test shaderpack: **Complementary Reimagined** (Iris 1.8.14). Earlier notes and
the DEV-0.14.0 commit message say Photon by mistake.

**Atlas mipmaps were never used (fixed in DEV-0.16.0, user-confirmed):**
the entity render types' texture shard calls `setFilter(false, false)` on every
`setupRenderState`, so atlas pages were sampled GL_NEAREST with no mips, although
the converter ships mips 0-4 and `MipTexture` enabled them once. Distant
surfaces shimmered; at full texture quality it became heavy noise, visible even
standing still (Complementary's TAA jitter re-samples every frame). Both
renderers now call `MapSurfaceRenderer.applyAtlasFilter` (NEAREST_MIPMAP_LINEAR,
like terrain) right after each state setup; `/src2mc_mipmaps on|off` to compare.
User-confirmed 2026-10-01: "on almost all blocks and props it looks amazing".
Full quality ran at a stable 23 fps on the furnace, so the user went back to
default quality; deeper mips for full quality (gutter 2^levels, a format
change) were not pursued.

**Cut-out mips (DEV-0.16.0):** user-confirmed fixed 2026-10-01. with mips in use, mesh
fences, grates and windows turned solid with distance: averaged alpha left the
holes partly opaque and the cut-out shader only drops alpha below 0.1.
`atlas::build_pages` now re-binarizes every cut-out region (recognised by its
already-binary alpha with some holes) in mips 1-4, keeping the level-0 share of
opaque texels, with a 4x4 ordered-dither rank breaking ties (a regular mesh
averages to one alpha everywhere). All 10 test bundles re-exported at default
quality, validated and installed.

**Full-quality textures (DEV-0.16.0, awaiting in-game check):**
`mod export --quality full` keeps every texture at its original Source
resolution (`atlas::TextureQuality::Full` in `analyze_resolution`; only an axis
over 4096, the client's output limit, is scaled down with the aspect kept).
No flag means the old 16 texels per block, byte-identical to before (furnace
bundle compared). Furnace at full: 1.1 GB bundle, 49 atlas pages, 442 textures
(default: 36 MB, 1 page, 725 textures; fewer textures because every use of a
material now shares one size). Not a format change. At ~89 MiB VRAM per
resident page, the client's 2 GiB texture budgets hold ~22 pages; residency
goes over budget rather than drop pages in view, but the budgets may need
raising for full-quality campaigns.

**Shadow-pass culling (DEV-0.15.0):** user-confirmed 2026-10-01: 60 to 67 fps
with it on, frame times as stable as before, no shadow or render faults. surfaces and props
drawn in the Iris shadow pass are now tested against Iris's own shadow frustum
(`ShadowRenderer.FRUSTUM`, read reflectively in `IrisCompat`; the advanced
shadow-culling frustum Iris uses for shadow terrain). The frustum in the
shadow pass's stage events is the player's culling frustum (NeoForge passes
`LevelRenderer.getFrustum()` from `renderSectionLayer`), which is why an earlier
shadow frustum test dropped casters behind the player and leaked sunlight.
`/src2mc_cull shadow on|off` to compare; the status lines count meshes outside
the shadow frustum. If Iris's field is missing, everything in range is drawn.

This document records the active implementation state and the empirical context
needed to continue the work in a new session. `AGENTS.md` contains mandatory
working rules. Durable requirements and design authority remain in
`docs/mod-requirements.md`, `docs/decisions.md`, `docs/format.md`, and
`mod/IMPLEMENTATION_PLAN.md`.

**Render measurement, draw order, indexed meshes (DEV-0.14.0):**
- `/src2mc_prop_status` (and its overlay) now has a surface line (draws,
  triangles, CPU ms, frustum/PVS rejections, state switches, shadow triangles)
  and a GPU line: GL_TIME_ELAPSED per phase (surfaces and props, opaque and
  translucent, and the shadow pass), 120-frame averages (`GpuTimer`). The prop
  root scan's average cost is shown too.
- Surface meshes are frustum-tested with their own vertex bounds, not the whole
  64-block region box.
- Opaque surfaces and props are drawn grouped by render state and nearest first
  inside a group (early depth rejection); surfaces no longer set up and clear
  render state per mesh. `/src2mc_draw_order sorted|unsorted` to A/B.
- First numbers (INFRA, Complementary Reimagined, 2026-09-30): frame 20 ms; GPU surfaces 1.2 ms
  opaque, props 6.1 ms opaque + 0.5 translucent, shadow pass surfaces 0.9 ms and
  props 6.8 ms. About 15 ms of the 20 are ours, almost all props. Sorted versus
  unsorted draw order made no difference, and the shadow pass (cheap fragments)
  costs as much as the main pass, so props are vertex-bound, not fill-bound.
- Hence indexed meshes (`/src2mc_indexed on|off`, default on, rebuilds all):
  `PackedVertices.index()` finds distinct vertices on the worker; upload still
  writes by triangle (Iris fills its extra attributes per triangle), then copies
  each distinct vertex's first occurrence and uploads a real index buffer. The
  status line shows uploaded vertices as a share of triangle corners. Shared
  vertices keep their first triangle's Iris tangent and mid-texcoord; user saw
  no visual change.
- Indexed result, user-measured 2026-09-30 at the same spot: frame 20 ms to
  14-16 ms (15 avg). GPU props 6.1 to 4.1 ms, prop shadow 6.8 to 4.7 ms,
  surfaces 1.2 to 1.0 ms, surface shadow 0.9 to 0.6 ms; prop VRAM 721 to
  335 MiB; 41% of triangle corners uploaded. Next lever: the prop shadow pass.

**Render culling fixes (DEV-0.13.0):** user-confirmed in game 2026-09-30: no
flicker, no pop-in, frame times steady at 20-22 ms. A review of the
visibility code found seven ways visible geometry could be hidden:
- GPU occlusion queries culled a prop batch whenever the camera stood inside its
  box: only the box's far walls rasterize, and they sit behind the room. This is
  the "props flicker and vanish while walking, never while flying" the user saw.
  Batches around the camera are now never queried or culled.
- Query results were reused over 0.25 blocks of movement with a fixed 0.5-block
  pad, which parallax near a door frame easily exceeds. Now 0.1 blocks and a pad
  that grows with distance; a box partly off screen is invalidated by any turn.
- Translucent props were occlusion-queried at `AFTER_PARTICLES` against glass,
  water, mobs and particles; translucent draws no longer query.
- Vanilla's entity translucent type writes depth, so the first translucent layer
  hid all translucent geometry behind it. Depth write is now off for
  translucent surfaces and props (`/src2mc_translucent_depth on|off` to compare).
- The PVS cluster was looked up at the camera block's integer corner, not the
  eye, so near doorways the neighbouring cluster's PVS was used (and on
  grid-aligned planes the lookup failed open). It now uses the eye position.
- Prop triangles lying exactly on a 16-block section plane were dropped.
- Surface regions were range-tested by their centre, leaving up to two sections
  of map missing at the render-distance edge; now by their nearest section.

**Export speed (DEV-0.12.0):** all 10 test maps now export in 48 s wall time
together (3 at a time), down from about 19 minutes; `sp_a3_end` alone went from
1130 s to about 24-40 s and `escape_02` from 537 s to 6-8 s. Every changed
stage produces byte-identical bundle entries (same fingerprints). Set
`SRC2MC_TIMINGS=1` to print per-stage times (`src/timing.rs`). What was slow:
- Prop collision shells tested every sixteenth-block in each triangle's
  bounding box, which is cubic in size for slanted triangles.
  `voxel::mesh::plane_candidates` now walks only the plane's slab (also used
  by `voxelize_triangle`), and large props split their triangles across
  threads.
- Textures were decoded, resampled and PNG-encoded one by one;
  `extract_materials` now records the requests in a first pass, produces them
  in parallel, and assigns in a second pass.
- Atlas pages and their mips are built in parallel, atlas PNGs encoded in
  parallel, and PNG ZIP entries are stored instead of deflated again (bundle
  about 3 % larger).

**Prop skins (DEV-0.12.0):** user-confirmed in game 2026-09-28.
The converter always drew skin family 0, so every prop placed with another skin
wore the default texture: furnace's rusted `watertreatment_tank_002` (skin 1)
came out clean white. About a third of INFRA's static props use a skin other
than 0 (1758 of 5149 in waterplant, 2784 of 8977 in furnace). The static prop
record's `m_Skin` (offset 32, every version since 4) and the entity `skin` key
are now read; `Models::get_skin` swaps each part's material for the family's
and falls back to skin 0 for a skin the model lacks, as Source does. No format
change: a skinned prop selects a model reference with the same mesh and its own
material slots (`docs/format.md` section 6). Mod unchanged.

Open follow-up: `$detail` textures (a second, finer grime/rust layer, e.g.
`detail/detail_rust_002` on the rusted tank) are still ignored. They tile at
their own `$detailscale`, so they need either a bigger baked texture or a
second texture in the mod renderer. Decide after the user sees skins.

Earlier rendering fixes, user-confirmed in game (2026-09-28):
- Inverted displacements (DEV-0.11.0): Hammer can invert a displacement,
  mirroring it through its base plane. That reverses the winding, and Source
  culls by winding. The converter used to force every displacement to face its
  base face, so INFRA's cave ceilings (56 of 135 displacements in
  `infra_c6_m4_waterplant`) were drawn inside out: see-through from inside,
  solid from outside. `bsp/displacement.rs` now takes the facing from the
  surface's winding, and drawing, the voxel solidify direction and terrain
  collision all follow it.
- Double-sided materials (DEV-0.10.0): Source's `$nocull` (fence meshes,
  grates, foliage cards) is read by the converter and written as the material
  flag `double_sided`. The mod adds a mirrored back face for such materials,
  wound the other way and lit from the side it faces.

Off-thread builds (DEV-0.9.0) are done and user-confirmed, including the see-through-regions fix (hidden-owner recheck).

## Product goal and history

The user is recreating the Half-Life 2 universe in Minecraft and wants automatic
generation of large base maps so the work does not consume all available time.
The project evolved from WorldEdit stone schematics, to colored vanilla blocks,
to KubeJS custom textures, to textures spanning many blocks, and finally KubeJS
3D prop meshes. That approach produced a pack over 9 GiB and a roughly
16384x16384 vanilla block-texture atlas that prevented Minecraft from starting
on weaker GPUs. An initial unplanned native mod was buggy and was scrapped. The
current repository is the deliberately redesigned implementation.

Source-to-Minecraft scale is fixed at 32 Source units per Minecraft block. Large
maps have already pasted successfully as one schematic using the converter's
large-schematic flag; map tiling is neither required nor desired. WorldEdit is
only a transport mechanism to read and paste schematics, not an integration API
for editing, moving, or transforming src2mc content.

## Fixed decisions and constraints

- Mod build system: Gradle.
- Java package/group: `dev.theredja.src2mc`.
- Minecraft namespace and mod ID: `src2mc`.
- Minecraft 1.21.1, NeoForge 21.1.235, Java 21.
- Test instance includes Sodium 0.8.13-beta.2, Iris 1.8.14-beta.1, WorldEdit
  7.3.8, Create, and other pack mods.
- Avoid Sodium/render-pipeline mixins unless absolutely necessary. The selected
  renderer is mod-owned VBO rendering outside Sodium's terrain internals.
- FRAPI is unavailable as a stable option on this exact 1.21.1 setup; only 0.9
  alphas were found when the compatibility spike was reviewed.
- Ordinary, anchor-preserving WorldEdit paste is supported. Rotated/transformed
  pastes are deliberately unsupported and may render incorrectly. No transformed
  paste detection or sentinel is required for this private tool.
- Anchorless surface data only receives diagnostics; automatic inference is not
  used. Reconciliation happens lazily as chunks load and manually through
  `/src2mc reconcile`; there is no WorldEdit event integration.
- A root is stored in the nearest free cell. It remains the authoritative data
  node while converted surfaces/prop meshes are derived render data.
- Client lookup uses the immutable loaded generation. Network bundle transfer is
  deferred; placement identity is synchronized through common/server state.
- Bundle identity is a deterministic fingerprint of sorted entry SHA-256 hashes,
  not ZIP byte identity or ZIP timestamps/metadata.
- Atlas tiles remain whole on one page and never span pages. Export includes
  gutters/padding and pregenerated mip levels to avoid neighboring-tile bleed.
- Texture decoded-RAM and estimated-VRAM cache budgets default independently to
  2 GiB and are configurable. They are ceilings, not allocation targets.
- Missing detail must not be guessed when a wrong choice would be difficult to
  remove. Visual correctness requires the user's in-game verification; image
  inspection by the assistant is explicitly insufficient.

## Environment and useful paths

- Repository: `/home/jakob/projects/source_to_mc`
- Mod development game directory: `mod/runs/client`
- Test world: `mod/runs/client/saves/TEST`
- Installed bundles:
  - `mod/runs/client/config/src2mc/bundles/hl2-phase3-test.src2mc`
  - `mod/runs/client/config/src2mc/bundles/infra.src2mc`
- HL2 BSP used for the main integration test:
  `/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_02.bsp`
- Generated HL2 output: `target/phase3-ingame/`
- Development jar: `mod/build/libs/src2mc-<VERSION>.jar` (currently
  `src2mc-DEV-0.11.0.jar`)
- WorldEdit schematic directory in this instance:
  `mod/runs/client/config/worldedit/schematics`

Do not add proprietary game assets to Git. The working tree contains extensive
intentional tracked and untracked implementation work. Preserve it; do not reset
or discard unrelated changes. Nothing from the latest work has been committed or
pushed unless a later session explicitly does so.

## Versioning

The converter and the mod share one version, written only in the root `VERSION`
file (currently `DEV-0.11.0`). `build.rs` passes it to the CLI's `--version`;
`mod/build.gradle` stamps it into the jar name and `neoforge.mods.toml`. FML
21.1 only loads a mod version starting with a digit (`^\d+.*`), so the
mods.toml gets `0.11.0-DEV`, the same version with the stage moved to the end.
`Cargo.toml` deliberately has no `version`: Cargo requires plain semver and
cannot hold the `DEV-` stage prefix. The minor number tracks the implementation
plan's phase in progress (Phase 5). The release workflow refuses a tag that
does not match `VERSION`. The `DEV` stage is only promoted with the user's
approval.

## Implemented architecture and phase status

Phases 0.5 through 4 are implemented and passed their required in-game checks.
Phase 5 (static props) is in progress. Phase 6 collision has not started.

Implemented foundations include:

- Versioned, validated, deterministic `.src2mc` campaign bundles.
- Converter-side BSP extraction, large Sponge v3 schematic export, immutable map
  metadata, sparse surface records, mesh payloads, material data, and paged atlas.
- Server/common placement lookup and client placement synchronization.
- Root/placeholder blocks and lazy/manual reconciliation.
- Mod-owned textured surface VBO renderer with section partitioning, arbitrary
  Source UV repetition, atlas residency, pregenerated mips, and diagnostics.
- Static prop root schema, client block-entity synchronization, runtime mesh
  decoding, arbitrary transforms, multiple material slots, repeated UV support,
  and section-partitioned prop VBO rendering.

Primary files for the next work:

- `mod/src/main/java/dev/theredja/src2mc/client/render/PropRenderer.java` — prop
  scheduling, VBO ownership, culling, drawing, expiry, and status command.
- `mod/src/main/java/dev/theredja/src2mc/client/render/PropTessellator.java` —
  transform, repeated-UV clipping, and section clipping.
- `mod/src/main/java/dev/theredja/src2mc/client/render/RuntimeMeshResidency.java`
  — asynchronous shared mesh decoding.
- `mod/src/main/java/dev/theredja/src2mc/client/render/MapSurfaceRenderer.java`
  and `AtlasPageResidency.java` — surface batches and shared atlas residency.
- `mod/src/main/java/dev/theredja/src2mc/world/Src2mcDataBlockEntity.java` — root
  payload normalization and client synchronization.
- `mod/src/main/java/dev/theredja/src2mc/bundle/` — validated runtime bundle,
  map, material, prop, mesh, and atlas representations.
- `src/output/mod_export.rs` — campaign export, prop/material collection, atlas
  construction, and map payload emission.
- `src/source/extract.rs` — Source extraction and the permanent volumetric-light
  model exclusion.

Important commands:

- `/src2mc status`
- `/src2mc validate`
- `/src2mc reload` (bundles also load by themselves in the background during
  game startup; the command is for picking up a bundle that changed on disk)
- `/src2mc reconcile`
- `/src2mc_render_status`
- `/src2mc_prop_status`
- The face-debug command reports records for a targeted block and was used to
  diagnose overlapping surface patches; inspect command registration for its
  exact current spelling before instructing the user.

## Verified surface-rendering behavior

The rendering spike initially caused a flood of OpenGL
`GL_INVALID_OPERATION: Array object is not active` errors. The VBO binding/order
was corrected and the flood stopped. The test wall then passed these checks:

- No flicker or geometry artifacts.
- Both sides appeared consistent.
- Ordinary blocks correctly occluded it.
- It looked stable near and far.
- Re-running the toggle made it disappear.
- Its intentional slight offset from the block surface prevented z-fighting.

Earlier diagnostic colors were repeatedly wrong due to vertex/color semantics.
The final clean probe colors were magenta, cyan, and gold with no visible split
halves or screen-space fuzzy lines.

The real HL2 map now renders complete textured surfaces with continuous texture
phase across multiple blocks, no noticeable initial FPS/VRAM regression, and no
missing-texture blocks after reconciliation. A strong red tint was fixed. Dark
quarter-block squares and a glitchy overlapping ground face were traced with
face debug to multiple coplanar Source records and fixed. The user confirmed the
result.

A healthy earlier surface status was approximately:

```text
src2mc render: sections=301, meshes=325, atlas resident=1/1, vram=85.3 MiB,
decode-pending=0 KiB, requests=9966163 (hit=9966046, miss=1, denied=0,
evicted=0, failed=0)
```

The 85.3 MiB number is src2mc's estimate for one 4096x4096 RGBA atlas page plus
its mip chain. It is not total process VRAM. `nvtop` also includes prop VBOs,
Minecraft/Sodium assets, shaders, render targets, driver allocation overhead,
and other GPU resources.

## Schematic and reconciliation lessons

The converter once emitted a schematic that WorldEdit rejected with
`ZipException: Not in GZIP format`. Sponge schematics must retain the expected
gzip container. This was fixed, and the correct output belongs under
`config/worldedit/schematics`.

The HL2 schematic pasted 5,843,184 blocks. Initially it logged many
`anchorless src2mc surface data is unsupported` warnings and displayed the
missing-texture checker. After loading the bundle and reconciling, a few blocks
still had missing texture; that was subsequently fixed. A successful check made
all Minecraft carrier blocks invisible, leaving only floating mobs visible at
night, as intended. The root anchor was also given a visible inventory/debug
texture so it can be found when necessary.

WorldEdit Sponge v3 places block-entity payload beneath a `Data` compound.
`Src2mcDataBlockEntity.normalizedPayload()` unwraps it. Client prop roots also
initially failed because vanilla `BlockEntity.getUpdateTag()` is empty and
`getUpdatePacket()` is null. The block entity now returns a payload copy from
`getUpdateTag()`, uses `ClientboundBlockEntityDataPacket.create(this)`, and sends
a server-side block update after replacing its payload.

## Static prop work and resolved defects

Initial prop status evolved from zero roots, to schema failures, to synchronized
roots:

- `roots=0/345 (unloaded=0, missing=1, invalid=344)`
- Then diagnostics isolated `schema=344`.
- After block-entity synchronization: `roots=344/345`, `built=344`, but initially
  zero section batches.

The zero batches were caused by the bundle exporting all model material slots as
fallback/untextured. Export now gathers prop materials before atlas creation,
includes prop-only materials, resolves their VMT/VTF data, and assigns render
classes. The regenerated HL2 bundle had 138 of 146 model slots textured, 81
unique prop textures, and a 233-texture atlas. Props then became visible.

Power poles were cut in half and some props were stretched because prop
tessellation discarded triangles with UVs outside the base texture range.
`PropTessellator` now performs repeat-aware UV clipping, including negative and
multi-repeat coordinates, with regression tests. The user confirmed the poles
and other truncated props were fixed.

Elongated shapes inside train-station windows were actual Source volumetric-light
meshes, not window geometry. These require Source's special additive volumetric
shader and render as incorrect wedges when treated as ordinary triangles. The
converter permanently and narrowly excludes
`models/effects/vol_light*.mdl` (case/slash normalized), while retaining real
windows and lamps. Seven placements were removed from the HL2 map, reducing it
from 345 to 338 exported props. The user confirmed this defect was fixed. A
repaste was unnecessary because stale roots absent from the immutable bundle are
ignored.

The latest known HL2 bundle details after this exclusion were:

- Bundle fingerprint:
  `71cb587c9f794eeee2f178b121e442080fe53da350b75a4c382d92009e926348`
- File SHA-256:
  `7a16a371fed41065df8560865c7876beb64daa5f09f37fc696f8b9be90c1ed01`
- Exported prop count: 338

Some model material slots were still unresolved during the earlier HL2 analysis:

- `models/props_combine//combine_bridge`
- `models/props_trainstation/trainstation_clock_glass001`
- `models/props_c17//doll01`
- `models/props_combine/com_shield001a`
- `models/props_combine//dispenser_sheet`

Double-slash normalization may resolve some; glass/shield materials may use
unsupported Source shaders. Phase 5 still requires explicit placeholder/error
handling rather than silently omitting genuinely unsupported submeshes.

## Current INFRA scalability result

The larger `infra_c4_m2_furnace` bundle exposed a prop scheduler defect:

- Bundle file size: 21,618,660 bytes.
- Exported props: 8,106.
- Unique runtime meshes: 487.
- `props.s2props`: 907,888 bytes.
- Atlas: one page.

The first prop renderer built exactly one prop per rendered frame, decoded on one
worker, scanned in bundle order, and expired meshes based on actual frustum draws.
At 60 FPS, 8,106 props therefore needed a theoretical minimum of 135 seconds;
bundle-order queuing caused nearby starvation, and turning away could evict nearby
props and force slow rebuilding.

The latest code changes, already compiled and automatically tested, now:

- Sort unbuilt candidates nearest-first.
- Build up to 32 props per frame, limited to approximately 4 ms of upload work.
- Bound decode look-ahead to the nearest 256 candidates.
- Decode two unique meshes concurrently.
- Keep all props inside the residency distance alive even when off-camera.
- Extend `/src2mc_prop_status` with nearby/waiting/build counts, mesh decode
  readiness/failure counts, and estimated prop VBO bytes.

The user verified that all props now load, with no observable loading/unloading
or microstutter. However, steady-state FPS fell from over 100 to a consistent
approximately 30 FPS. In the center, every prop was considered nearby and all
8,106 were retained. Final status:

```text
src2mc props: roots=8106/8106 (unloaded=0, missing=0, schema=0, campaign=0,
map=0, identity=0), built=8106, section batches=12716, nearby=8106
(waiting=0, built-last-frame=0), mesh decode=487 ready/0 pending/0 failed,
estimated VBO=671.7 MiB
```

During warmup, the renderer built roughly 11-17 props per frame within its time
budget. `nvtop` showed about 1.8 GiB for the whole Minecraft process. The user
considers that memory amount acceptable—some shader configurations use around
4 GiB at 3440x1440—but the 30 FPS steady-state performance is not acceptable.

## Gate 1 profiling result (measured 2026-09-06)

Gate 1 counters are implemented in `PropRenderPerf` and wired into
`PropRenderer`'s `/src2mc_prop_status`. The user measured the INFRA furnace map
at the center (all 8,106 props nearby, steady 30 FPS):

```text
src2mc prop perf last-frame: visible-props=6635, visible-batches=10645/12716
frustum-tested, draws=10645 (translucent=307), triangles=5232371,
render-cpu=28.76 ms, build-cpu=1.19 ms, evicted=0, builds=0
window-120f: draws=10645, render-cpu=28.50 ms, build-cpu=1.11 ms
```

Conclusions:

- The 30 FPS frame is dominated by ~28.8 ms of CPU-side draw submission:
  10,645 draws at roughly 2.7 µs each (setupRenderState + bind +
  drawWithShader + clearRenderState). `nvtop` shows only about 35% GPU usage,
  confirming a CPU/OpenGL submission bottleneck rather than GPU throughput.
- Frustum culling works (12,716 resident batches reduced to 10,645 tested
  visible; counts drop when turning).
- "Props behind a wall" still cost submissions because the mod-owned D16
  backend has frustum-only culling; occlusion is gate 4 (PVS) territory. Gate
  2's merged batches make this mostly irrelevant to frame time.

## Approved gate 2 design (agreed 2026-09-06, implementation started)

Merge prop geometry into one aggregate VBO per `(map placement, 16^3 section,
atlas page, render class)` instead of one VBO per prop/section/page/class.
Expected to reduce ~10,645 draws to a few hundred.

- New plain-Java, unit-testable bookkeeping class `PropBatchAggregator` owns
  contributors, dirty keys, and assigned mesh values per aggregate key.
- `PropTessellator` is untouched. A built prop tessellates once per frame into
  per-aggregate contributions and registers them; uploads happen only in the
  rebuild pass.
- Rebuild strategy (user decision): re-tessellation, not CPU triangle
  retention. Dirty aggregates re-tessellate all registered contributors from
  the cached decoded meshes (`RuntimeMeshResidency` never evicts within a
  generation, so re-requesting is safe). No CPU-side triangle storage.
- Rebuild pass runs after builds each frame, nearest-first, bounded by a
  ~4 ms budget; at least one dirty aggregate is rebuilt per frame. Stale
  buffers keep rendering until rebuilt (brief ghost/delay only during churn;
  steady state has zero rebuilds).
- Draw path iterates aggregates, frustum-tests section AABBs, sorts opaque by
  page and translucent far-to-near per section (R5/D6 order).
- Perf counters extended: visible aggregates, frustum tests, draw calls,
  triangles, render/build/rebuild CPU, rebuilds, dirty backlog, evictions.
- Lifecycle: expiry/status-flip unregisters contributions and dirties
  affected aggregates; scheduler, decode residency, and grace frames are
  unchanged.

## Gate 2 implementation status (2026-09-06)

Implemented and automatically tested; not yet verified in game.

- `PropBatchAggregator<K, P, M>`: plain-Java contributor/dirty/value
  bookkeeping; GL resources stay in the renderer. 8 unit tests.
- `PropRenderer`: per-prop uploads removed. `buildProp` tessellates (via
  `tessellateProp`, unchanged semantics), caches the result per frame in
  `tessCache`, stores `ROOT_DATA` for re-tessellation, and registers
  `PROP_CONTRIBUTIONS` on aggregate keys. `rebuildDirtyAggregates` rebuilds
  nearest-first within a 4 ms budget (at least one per frame), re-tessellates
  contributors through the per-frame tessellation cache, uploads merged
  buffers, and closes replaced ones. Empty aggregates are deleted at
  rebuild; removed props leave a brief ghost until their aggregates rebuild.
- Draw path iterates aggregates only; opaque sorted by page, translucent
  far-to-near per section. Per-prop visibility metrics were replaced by
  aggregate metrics (visible aggregates, frustum-tested, draws, rebuilds,
  dirty-left, rebuild-cpu).
- Removal nuance: `removeProp` now unregisters contributions instead of
  closing meshes; `M_EVICTED_AGGREGATES` counts aggregates newly dirtied by
  expiry. Expiry, decode residency, grace frames, and scheduler behavior are
  unchanged.
- Automated results: 39 mod tests pass (31 previous + 8 new aggregator
  tests); jar builds against the INFRA bundle. No Rust/converter changes.
- Next: the user must retest the INFRA furnace map in game (gate 7): steady
  FPS, `/src2mc_prop_status` perf lines, pop-in during fast movement/turning,
  missing or visually incorrect props, and ghost artifacts while moving.

## Gate 2 verified result (user-measured 2026-09-06)

The user retested the INFRA furnace map and confirmed gate 2:

- Steady FPS recovered from ~30 to ~120 with no prop visual defects.
- Steady-state counters: `aggregate batches=534` (was 12,716 per-prop
  batches), `draws=481 (translucent=77)`, `render-cpu=1.30 ms` (was
  28.76 ms), `build-cpu=0.90 ms`, `rebuilt=0, dirty-left=0`, same ~671.7 MiB
  VBO estimate and 6.0M visible triangles.
- Remaining complaint: no occlusion culling — looking at the map from above
  runs ~100 FPS versus ~350 FPS at the sky; props behind solid walls still
  cost. Expected: the D16 backend is frustum-only; occlusion is gate 4 (PVS)
  territory. The remaining gap's attribution (props vs vanilla terrain vs
  the surface renderer) still needs the A/B toggle measurement.

## Post-gate-2 additions (2026-09-06)

- `/src2mc_prop_toggle`: client command toggling prop drawing for A/B FPS
  measurement at identical viewpoints. Built geometry stays warm; build and
  rebuild passes keep running so re-enabling is instant.
- Draw-state grouping in `draw()`: consecutive aggregates sharing bundle
  fingerprint, atlas instance, page, and render class reuse one
  `setupRenderState`/`clearRenderState` pair (previously one pair per draw).
  Opaque sorting is by `(render class, page)`; translucent keeps its
  far-to-near order and only merges state for consecutive identical types.
  New `state-switches` counters verify grouping in `/src2mc_prop_status`.
- 39 mod tests pass; jar builds. Not yet re-verified in game; the user
  should retest FPS and visually confirm nothing changed.

## Gate 4 justification measurement (user A/B, 2026-09-06)

With `/src2mc_prop_toggle`, identical viewpoints on the INFRA furnace map:

- View A (whole map from above): props on 110 FPS, off 230 FPS → props cost
  ~4.7 ms/frame; 534/534 aggregates frustum-visible, 6.5M triangles.
- View B (indoors behind a solid wall): on 150 FPS, off 270 FPS → ~3.0 ms;
  343/534 aggregates visible, 4.3M triangles drawn and depth-rejected.
- `render-cpu` is only 0.8-1.3 ms and `state-switches=3`: CPU draw
  submission is solved; the remaining prop cost is GPU-side processing of
  triangles that occlusion culling would reject. This is the agreed gate 4
  trigger, so Source BSP visibility-cluster (PVS) work has started.
- Known limitation to record: from outside the map (View A), Source's own
  PVS keeps everything visible, so gate 4 targets indoor/ground-level views,
  not top-down diagnostics.

## Gate 4 PVS implementation status (implemented, awaiting user retest)

Implemented since the justification measurement:

- Converter (`src/bsp/pvs.rs`): decodes the Source `VISIBILITY` lump into
  per-cluster PVS rows plus leaf boxes. Verified Valve semantics (from the
  user's expert, after bsp_tool/vbsp disagreed): rowBytes =
  (clusterCount + 7) >> 3; nonzero byte copies 8 clusters LSB-first;
  `00 NN` emits NN zero bytes (NN == 0 invalid); the final zero run of a
  row may nominally overrun the row and must be clipped, consuming both
  run bytes. `vbsp` 0.9.1's `visible_clusters` (cluster-skip) is wrong for
  real compiler output; row 24 of d1_trainstation_02 is an overlong final
  run that only decodes with clipping. `LeafSectionIndex` groups leaf
  boxes into 16-block sections (floor-division, negative-coordinate safe).
- Bundle format (`docs/format.md` §12, normative): `pvs.s2pvs` v1 — leaf
  records (i16 cluster + inclusive i16 box, sorted/deduped, cluster >= 0),
  per-section cluster sets (empty omitted), cluster_count x row_bytes
  bitsets. Map metadata gains an optional `pvs` path; §10 gains PVS
  limits. Fixture fingerprint is unchanged for maps without PVS.
- Export (`src/output/pvs.rs`, `mod_export.rs`): every 16-block section a
  transformed prop box spans collects the clusters of overlapping leaves;
  sections without clusters are omitted; encoder enforces limits. PVS is
  optional per map — unusable visibility data silently renders unfiltered.
- Runtime (`PropVisibility`, `BundleSchemaValidator`, `BundleMap`,
  `PropRenderer`): camera cluster resolved from leaf boxes once per
  block-move; aggregates rejected when their cluster set is disjoint from
  the camera row. Fail-open (unfiltered) whenever the camera is outside
  any leaf box, there is no table/row/section, or the section's cluster
  set is empty. New `M_PVS_REJECTED` counter and `pvs=cluster N/rejected
  M/off` status.
- Validation: 478 Rust tests pass (new: overlong-run clipping, malformed
  `00 00` rejection, section clusters surviving skipped negative-cluster
  leaves); Java tests + jar build pass; HL2 bundle regenerated with
  `pvs.s2pvs` (clusters=1049, rowBytes=132, leaves=1081, sections=170,
  908 distinct section clusters) and fingerprint `25e8c877...`; installed
  to the client bundles dir.
- Fixed during bring-up: `LeafSectionIndex::clusters_in_section` indexed
  `visibility.leaves` with the accepted-leaf index, so any map whose first
  BSP leaves have cluster -1 exported `0xFFFF` section clusters, which the
  runtime validator correctly rejected ("pvs section clusters are not
  uniquely sorted"). The index now stores each leaf's cluster with its box.

Pending: user in-game retest (gate 7) on the INFRA furnace map — stand in
the earlier View B room behind the wall; `/src2mc_prop_status` should show
`pvs=cluster N/rejected M` with rejected > 0, higher FPS with props on, no
missing props in the visible room, and no pop-in when turning. View A (map
from above) will not improve; Source's PVS keeps everything visible there.

The likely primary bottleneck is not the texture budget. The renderer currently
owns VBO batches per individual prop/section/page/render-class. It therefore has
12,716 resident batches and may perform a very large number of independent draw
submissions after frustum culling. This must be measured rather than assumed.

## Agreed next performance plan (steps 1, 2 and 4 implemented)

The user explicitly requested only a proposal in the last performance turn. No
performance work beyond the scheduler changes above has been implemented yet.
Proceed in these gates:

1. Add profiling counters for resident versus visible props/batches, actual draw
   calls, submitted vertices/triangles, CPU render time, build/rebuild time, and
   eviction. Keep atlas, VBO, and total-process GPU memory distinct.
2. Merge prop geometry by `(section, atlas page, render class)` rather than owning
   separate VBOs per prop. Retain per-root logical geometry/lifecycle metadata,
   mark only affected aggregate batches dirty, and rebuild dirty sections within
   a frame-time budget. This is the highest-confidence first optimization and
   should reduce thousands of submissions to hundreds without lowering quality.
3. Improve spatial residency using transformed model bounds, distinct load and
   unload radii (hysteresis), and an outer prefetch ring. Do not regress to visible
   pop-in when turning or moving.
4. If merged batching is insufficient, export and use Source BSP visibility
   clusters/PVS so rooms and map regions impossible to see from the camera's
   cluster are rejected. This is especially relevant for indoor INFRA maps and
   avoids Sodium internals.
5. Only if measurements still require it, cache visible-batch lists and consider
   a safe multi-draw backend. Do not introduce LOD, texture-quality reduction, or
   Sodium mixins without new evidence and user agreement.
6. Later expose prop VBO budget, prefetch radius, unload grace, build/rebuild time,
   and optional PVS use as configuration. Eviction must be measured, distance/LRU
   based, and must not silently discard visible nearby props.
7. After each major optimization, ask the user to retest the INFRA furnace map
   and report steady FPS, status counters, pop-in during fast movement/turning,
   and any missing or visually incorrect props.

## Remaining Phase 5 work

Besides performance, Phase 5 still requires:

- Root placement and break cleanup completion/verification.
- Middle-click item metadata and pick-block/re-place behavior.
- Placeholder meshes/materials and detailed errors for missing or unsupported
  model/material references.
- Ordinary WorldEdit paste, pick-block/re-place, reload, and reconciliation tests.
- Verification that removing a root removes derived rendering and placing a
  copied root recreates it.
- Transformed WorldEdit paste remains deliberately unsupported.

Do not start Phase 6 collision until the Phase 5 rendering/lifecycle and large-map
performance gates are complete.

## Off-thread mesh builds (DEV-0.9.0, implemented 2026-09-28, user-confirmed 2026-09-28)

Problem: joining a world with large maps ran at about 5 fps for one to three
minutes. Every surface region was tessellated and lit on the render thread under
a 150 ms per-frame load budget, props under 4 + 4 ms, and most regions were built
several times: again when their atlas page arrived, and again when their chunks'
light arrived (the client applies chunk light through `ClientLevel`'s light
queue, a tenth of the queue per frame, after the blocks).

Now (all in `mod/src/main/java/dev/theredja/src2mc/client/render/`):

- `MeshBuildPool`: a third of the cores, at most six, just below normal priority.
  Workers tessellate and light; they return `PackedVertices` (plain arrays). The
  render thread only fills the `BufferBuilder` and uploads, because Iris stamps
  its captured entity ids from render-thread state into every vertex.
- `WorldSnapshot`: an immutable copy of block states and light around a build
  (`BlockAndTintGetter`). Sections with a data layer are copied; sections without
  one take the engine's own answer per column (sky: bottom row of the next lit
  section above; block: 0). Copies are shared by the builds of both renderers
  within one frame and dropped at the next. A first version kept them for 1 s
  with event-based invalidation, and on joining it served copies taken before
  chunks arrived: regions were built with almost all their surface blocks
  "missing" (probe: hidden 20854 of 20986 at build, 0 live) and stayed
  see-through until `/src2mc_rebuild_meshes`.
- `ChunkLightTracker`: NeoForge posts the client chunk load event before the
  chunk's light is queued, so a marker queued from the event re-queues a second
  marker that runs after the light. Builds whose chunks (within render distance)
  are not light-ready wait, for at most 3 s. The light watcher does not take a
  baseline hash from a chunk whose light is still pending.
- Surfaces: nearest region first, up to 2 × workers in flight, 3 ms/frame for
  snapshots, 4 ms/frame for uploads (one always goes). An invalidation bumps the
  region's version; a build that lands against an older version is discarded.
  A build that throws on a worker is retried on the render thread against the
  live level. The rebuild on atlas page arrival is gone (the mesh never depended
  on the page). Meshes are indexed by region, and region bounds are cached per
  placement, which removes the per-frame walk over every mesh for every region.
- Props: a prop is tessellated on a worker only to learn its batches
  (registration); sections are then rebuilt whole on a worker, every page and
  class at once. Section rebuilds wait while registrations are running (at most
  3 s), so a section is not rebuilt once per arriving prop. An overtaken rebuild is
  still uploaded but its batches stay dirty. The per-frame walk over every prop
  only runs when something can have changed.
- Counters: `/src2mc_render_status` (workers, in flight, waiting for chunk light,
  discarded, failed, worker time, sections copied/reused, dispatch and upload ms)
  and `/src2mc_prop_status` ("Builds" line).

In-game result (2026-09-28): the user reports loading is "literally instant",
1-2 s, down from one to three minutes at about 5 fps. Bug found afterwards: after
a paste, regions far from the player stayed see-through until `/src2mc reload`;
the lower the render distance during the paste, the more of the map (all of it
at 4 chunks, none at 24). The probe showed the builds really had no owner
blocks yet, and the blocks arrived later with nothing triggering a rebuild.
Before, the rebuild when an atlas page arrived hid this. Dropping the cross-frame
copy cache did not help. The fix is `HIDDEN_OWNERS` in `MapSurfaceRenderer`:
every built region's missing owner cells are re-checked against the live world,
4096 per frame, and the region is rebuilt when one appears. It is user-confirmed.
Which message arrived late is still unknown; a likely one is that the client
ignores surface-change payloads while it has no placement, and the placement is
registered near the end of the paste. For
diagnosis, `/src2mc_region_probe` lists, for the camera's region and its eight
neighbours on the same level:
- state (built, building, waiting for light, not built) and version;
- what the last build saw (fragments drawn and hidden, whether the light wait
  timed out, unloaded chunks);
- how many owned fragments the live world would hide now.

The probe also logs each line.

## Sub-block collision (DEV-0.8.0, implemented 2026-09-27, user-confirmed 2026-09-28)

Design: `docs/decisions.md` D18; format: `docs/format.md` section 14.

- Converter: `src/voxel/collision.rs` computes each cell's solid volume in
  sixteenths from the converted brushes (octree against the brush planes) and
  the displacements (height field along the inward axis, one sixteenth
  thick: Source terrain has no thickness, and following the grid's
  `solidify` backing put 1.5 blocks of collision into the ceiling of the
  INFRA basement under an upstairs terrain floor; user-reported, fixed
  before commit). A map block that no brush or terrain reaches (that
  backing) collides as nothing. Rounded outward; separate pieces stay separate
  boxes. `src/output/cell_collision.rs` writes `maps/<id>/collision.s2coll`.
- Ownership mirrors the surfaces: a map block's own volume is its shape (a
  full block needs no entry). Volume in an air cell hangs off the
  face-adjacent block it touches most, as a shape reaching into that cell
  (-16..32 sixteenths). Untouching volume and every thin brush get a
  `src2mc:carrier` block in the schematic. Hollowed interior cells get nothing.
- INFRA furnace: 123,449 shaped cells, 21,784 carriers, 29,303 attached
  pieces, 14,317 blocks with no volume (mostly terrain backing); the whole
  export takes about 12 s.
- Mod: `bundle/CollisionTable.java` (decode and validate),
  `world/CollisionShapes.java` (per-side, per-dimension placement snapshot,
  rebuilt on a placement epoch or generation change, the server's only on the
  server thread; shapes built lazily per shape id). `Src2mcSurfaceBlock` and
  the new `Src2mcCarrierBlock` are `dynamicShape().forceSolidOn()`; light,
  occlusion shape and skylight stay fixed (surface: solid cube, carrier:
  nothing). The carrier is `noOcclusion().replaceable()`; that was decided
  without asking and is easy to flip if the user wants carrier cells
  unbuildable. The schematic reader and the placer accept `src2mc:carrier`.
- Lithium 0.15.4 (checked in its bytecode): the sweeper calls
  `getCollisionShape(level, pos, ctx)` and checks edge cells through
  `hasLargeCollisionShape()`, like vanilla; that is true for dynamic-shape
  blocks. Vanilla `BlockCollisions` passes the `LevelChunk`.
- Shape building: `world/TableShape.java` builds a shape in one pass from its
  box edges (an `ArrayVoxelShape` subclass, the only way to its constructor).
  `Shapes.or` box by box took 55.6 s for INFRA's 14,655 shapes (up to 251
  boxes each), which the user saw as 10-30 s of lag entering new areas; now
  0.11 s, and all shapes of a table are prebuilt off-thread on first use.
  The owner-cell cache is keyed by `CollisionTable` identity: keying it by the
  `SurfaceTable` record hashed every fragment per lookup and gave 0 fps.
- Outline: a map block is outlined as its collision; with no collision it is
  a full cube only if it owns a drawn fragment, otherwise nothing and
  replaceable by building (user asked to lose the black outlines of empty
  backing blocks). Blocks stay in the world so lighting is unchanged.
- Commands: `/src2mc collision` (status and counters), `/src2mc collision
  exact|full` (A/B against full cubes), `/src2mc collision probe` (block
  looked at, feet and below: shape, boxes, table entry).
- Tests: 11 Rust volume tests and 4 encoder tests; Java `CollisionTableTest`
  and `CollisionShapesTest`. All Rust (525) and mod (86) tests pass; the jar
  builds against the regenerated INFRA bundle.

Needs the user in game (install the regenerated bundles and schematics,
`/src2mc reload`, and place the maps again, since the schematics now hold
carriers):
- Floors part-way up a block: you stand on the visible surface.
- Low ceilings and door frames: headroom matches what is drawn.
- Stairs and ramps: walking up works (step height 0.6).
- Fence and railing gaps stay passable; thin plates are solid.
- Breaking and re-placing blocks keeps the right shapes; building into a
  carrier cell works.
- No FPS or TPS regression while moving through dense areas.

Prop collision, first version (awaiting in-game check):
- `bsp/rawprops.rs` reads `m_Solid` (byte 30); entity props read the `solid`
  key (default 6). 0 is walk-through, 2 the world AABB (`box_volume`), 6 the
  drawn mesh's shell a sixteenth thick (`shell_volume`), since `.phy` is not
  parsed. Props under `props.collision_min_size` (48 units) get none.
- `mod_export.rs` `add_prop_collision` merges it (`collision::add_props`):
  into a partial block's shape, else a carrier. Roots may land on carrier
  cells and then carry that shape (`Src2mcDataBlock` `carriesCollision`,
  `prop_root` is `dynamicShape()`).
- INFRA furnace: 6,117 solid props, 94,852 prop cells, 84,042 carriers,
  56,325 distinct shapes (1.79M boxes, up to 615 per shape; built in 0.6 s),
  12 MB table.
- Not done: removing a root does not remove its prop's collision; `.phy`
  hulls; `/src2mc collision full` also turns prop collision off.

## Exact per-cell surfaces (DEV-0.6.0, implemented 2026-09-27, user-verified in game)

Motivation: open areas looked right, tight spaces (hallways, low ceilings, doors,
windows) fell apart because surfaces were snapped to 0.5-block micro-patches and
collision is whole blocks. See `docs/decisions.md` D17 and `docs/format.md` §5.

- Converter: `src/voxel/fragments.rs` cuts exact polygons per cell and picks the
  owner block. `convert.rs` `exact_polygons` reads the BSP face lump and
  displacement triangles, and traces each face to its brush (`SolidLookup`) so
  per-brush skip rules and the 3D-skybox cut still apply. The face lump's
  `plane_num` already faces outward; flipping by `side` is wrong (verified on
  INFRA: 8573 of 9604 side=1 faces only matched unflipped).
- Surface table is now v2 (fragments with an owner-offset flag byte). Thin
  brushes are no longer exported as `*brush/N` props.
- Mod: fragments render at exact positions. An owned fragment is hidden once its
  owner `src2mc:surface` block is gone. `SurfaceChangeTracker` sends dirty
  sections to clients per tick. Clients recheck owner cells on chunk load. The
  sky bake marks owner cells opaque.
- INFRA furnace: 254,657 fragments, 180,930 owned, 73,727 unowned (64k of those
  are thin-brush cells), 2 faces with no brush.
- Props are placed at their exact Source origin. The settle and sub-block snap
  corrections are gone from mod export: they fitted props to the snapped grid
  and pushed them off the exact floors (user-reported floating props). The
  `props.snap*` config keys were removed; `settle` still applies to `convert`.
- Light bleed at wall/floor edges (DEV-0.7.0, awaiting in-game check): smooth
  light sampled cells behind off-grid and thin walls. `SurfaceOcclusion` drops
  any neighbour cell the vertex cannot see past the exact surfaces. Toggle:
  `/src2mc_light_occlusion on|off`; counters are in `/src2mc_render_status`.
  The first attempt changed nothing. `/src2mc_light_probe` (writes
  `config/src2mc/light-probe.txt`) showed the real cause: a floor 1/8 block
  into its block leaves every weighted corner solid, and the brightest-neighbour
  fallback read sky 14 through the block in a room at sky 0. Surfaces now fall
  back to visible open corners, then the nearest visible open cell. Props use
  the same sampler and visibility test.
- Second bleed case (probe at a wall foot below a displacement floor): the
  vertex saw sealed air pockets that hollowing leaves inside thick floors, and
  they held sky 12. The bake only treated fragment-owning blocks as opaque, so
  daylight seeped through the hidden map mass. The occlusion mask now holds
  every schematic block plus the thin-brush cells (format §13 semantics
  widened; binary unchanged).
- Collision was still full blocks at this point; see "Sub-block collision"
  above.

## Shaderpack shadow bug (fixed, user-verified)

Symptom: with Complementary Reimagined, map geometry cast shadows only within a
few blocks of the player and was lit flat past that, for the rest of the
session. It survived shader toggles; only a mesh rebuild with shaders off
cleared it.

Cause: Iris swaps `DefaultVertexFormat.NEW_ENTITY` for its extended entity
format and writes the currently captured entity/block-entity/item id into every
vertex. Our meshes are static VBOs, so the stray id became permanent, and
Complementary (`ENTITY_SHADOW=-1`) drops entity-labelled geometry from the
shadow map. Fixed in `aa534b3`: both upload paths zero the captured ids while
building. `/src2mc_iris_entity_id captured` restores the old behaviour for an
A/B test. The Iris opt-out that does not work (`ImmediateState.skipExtension`) is
recorded in `mod/docs/iris-compat.md`.

Diagnostics kept from the investigation: `/src2mc_client_light` (the client's
own sky-light bake), `/src2mc_rebuild_meshes` (rebuild every mesh without
touching the generation or the bake), and the shadow-pass, light-provenance and
vertex-stride lines in `/src2mc_render_status`.

## Validation commands

From repository root:

```sh
cargo fmt --all -- --check
cargo test
git diff --check
```

From `mod/` (Gradle needs access to the existing user cache):

```sh
./gradlew test jar \
  -Dsrc2mc.testBundle=/home/jakob/projects/source_to_mc/mod/runs/client/config/src2mc/bundles/infra.src2mc
```

Regenerate the HL2 integration bundle from repository root:

```sh
cargo run -- mod export \
  --campaign hl2-phase3-test \
  --out target/phase3-ingame \
  '/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_02.bsp'
```

The INFRA furnace map lives inside a VPK; the tool reads it directly
(`src2mc maps <vpk>` lists candidates):

```sh
cargo run -- mod export \
  --campaign infra \
  --out target/infra-ingame \
  '/mnt/games/SteamLibrary/steamapps/common/infra/infra/pak02_dir.vpk:maps/infra_c4_m2_furnace.bsp'
```

Install it only when regeneration is actually required:

```sh
install -m 0644 \
  target/phase3-ingame/hl2-phase3-test.src2mc \
  mod/runs/client/config/src2mc/bundles/hl2-phase3-test.src2mc
```

Recent automated result: the Java mod tests and jar build passed against the
actual INFRA bundle. At the most recent full converter checkpoint, all 467 Rust
tests passed. Re-run the full suites after further modifications rather than
assuming that old result remains current.

## PVS repair (2026-09-06)

The original Gate 4 PVS implementation was unsafe and has been replaced before
any further performance work:

- `vbsp` exposes visibility bytes after the `dvis_t` header, but Source PVS
  offsets are relative to the full lump. The decoder now subtracts
  `4 + cluster_count * 8` with checked arithmetic and rejects every table
  whose row does not include its own cluster. The previous decoder failed this
  invariant on the real HL2 and INFRA bundles.
- PVS format is now **v2**. It serializes the world BSP plane tree, in
  map-local coordinates, rather than using overlapping outward-rounded leaf
  AABBs to select a camera cluster. The client performs exact point-leaf
  traversal and fails open on a split plane, solid/outside leaf, malformed
  node, or cycle. Section-to-cluster sets remain conservative AABB-derived
  data only, which is safe for rejecting aggregate prop sections.
- The schema validator checks v2 node/terminal references and self-visible
  rows. Encoding errors are propagated instead of being hidden by `.ok()`.
- Automated evidence after the repair: 478 Rust tests; focused PVS tests;
  Gradle `test jar`; and Gradle validation against the newly regenerated real
  INFRA furnace bundle all pass. `git diff --check` passes.
- `target/infra-pvs-repair/infra.src2mc` was regenerated and copied to
  `mod/runs/client/config/src2mc/bundles/infra.src2mc` (SHA-256
  `9f89f8fe949cf47ed345c155ab02643a8a5b4b24f9d6f3431ded59da0ce7dcaf`).
  The running game must be restarted/reloaded with the freshly built mod code;
  old v1 PVS bundles are deliberately incompatible and must be regenerated.
- The same requirement applies to every bundle in the client directory: the
  HL2 test bundle was also regenerated as v2 and installed at
  `mod/runs/client/config/src2mc/bundles/hl2-phase3-test.src2mc`. A reload
  validates all bundles, so one remaining v1 bundle prevents the entire
  generation from loading.

Required manual gate before using PVS numbers for performance decisions: load
the INFRA furnace test map, run `/src2mc reload`, then `/src2mc_prop_status`.
Confirm it reports `pvs=cluster ...` while standing inside normal rooms, and
walk through several room/doorway transitions: no props may disappear unless
they are genuinely out of sight. Check the old indoor behind-wall viewpoint
again and record FPS, visible aggregates, triangles, and PVS-rejected count.
