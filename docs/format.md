# src2mc mod interchange format

Status: **version 1 work in progress**. Sections 1–12 are normative. Their
writer-side contracts are implemented by the converter and the Phase 2 mod
reader validates them. Rendering consumers are implemented in later phases.
The abandoned prototype is not an input format and has no migration path.

## 1. Container and names

A campaign bundle is a ZIP file with the extension `.src2mc`. Entry names are
UTF-8, relative, forward-slash-separated paths. They are at most 240 UTF-8
bytes. Empty components, `.`, `..`, backslashes, control characters, drive or
URI-style colon components, leading slashes, and trailing slashes are invalid.
Duplicate names are invalid after exact UTF-8 comparison.

`manifest.json` is the only bootstrap entry and is reserved. Directory entries
are unnecessary. Readers must use names, never ZIP entry order.
Entries may be stored or deflated; the converter stores PNG and Ogg entries,
which are compressed already, and deflates the rest. Content IDs and the fingerprint
cover entry bytes, not the ZIP encoding.

## 2. Canonical payloads

JSON owned by this format is UTF-8 without a byte-order mark, uses the declared
field order and lexicographically sorted object maps, contains no insignificant
whitespace, and ends in exactly one LF. Strings use JSON escaping as emitted by
Serde JSON. Optional fields are governed by each payload schema; exporters may
not interchange missing and `null`.

Numbers use JSON's shortest round-tripping decimal representation. Format
floats are finite IEEE-754 binary64 values. NaN and infinities are errors;
negative zero is normalized to positive zero before encoding. Payload-specific
schemas may narrow these rules. Binary integers and IEEE-754 fields are
little-endian. Binary payloads begin with their own magic and version rather
than relying only on the manifest version.

PNG texture bytes and binary mesh bytes are already their canonical payloads.
An exporter must control their encoder settings; decoded pixels or equivalent
triangles are not substituted when hashing.

## 3. Content IDs and hashes

`sha256` and all content IDs are 64 lowercase hexadecimal characters. A
content ID is SHA-256 over the exact canonical payload bytes and is used for
deduplicated assets, for example `textures/<id>.png` and `meshes/<id>.s2mesh`.
Human Source paths remain diagnostic provenance and never determine identity.

The manifest lists every payload entry except itself, sorted by entry path:

```json
{"format":"src2mc-campaign","version":1,"campaign_id":"hl2","fingerprint":"…","entries":[{"path":"maps/d1_trainstation_01.json","size":123,"sha256":"…"}]}
```

The fingerprint is SHA-256 over this unambiguous byte sequence:

1. ASCII `src2mc-manifest-v1` followed by a zero byte;
2. for each sorted entry: path byte length as `u32`, path UTF-8 bytes,
   uncompressed size as `u64`, and the 32 decoded hash bytes.

ZIP compression, entry order, timestamps, permissions, creator OS, extra
fields, comments, and the complete archive byte stream are outside identity.
Validation hashes uncompressed entry payloads and recomputes the fingerprint.

## 4. Writer requirements

The converter rejects a non-`.src2mc` output name, unsafe or duplicate entry
paths, a user-supplied `manifest.json`, non-finite format floats, and a
content-address collision whose bytes differ. It deduplicates identical
canonical content bytes. The current writer also emits sorted entries and
fixed permissions for reproducible diagnostics, but readers must not depend on
those incidental ZIP properties.

## 5. Map surface table

Each map's visible world geometry is stored at `maps/<map-id>/surfaces.s2faces`
as exact polygons, one per map-local cell they fall in. Nothing is snapped to
the block grid: a wall 8 units off a block boundary is drawn 8 units off it.
The payload begins with this fixed little-endian header:

| Field | Type | Value |
| --- | --- | --- |
| magic | 8 bytes | `S2FACE\0\0` |
| version | `u32` | 2 |
| UV-region count | `u32` | number of following UV records |
| section count | `u32` | number of following section buckets |
| fragment count | `u32` | total fragment records in all buckets |

UV regions follow in lexicographic order of their canonical IEEE-754 bit
patterns. Each consists of eight finite `f64` values: the four coefficients of
`s = ux*x + uy*y + uz*z + uoffset`, then the corresponding four coefficients
for `t`. Coordinates are map-local Minecraft block coordinates. Signed zero is
canonicalized to positive zero before deduplication and writing.

Each non-empty 16x16x16 map-local section then contains its signed `i32` X, Y,
and Z section coordinates, a `u32` fragment count, and that many
variable-length fragment records:

| Field | Type | Meaning |
| --- | --- | --- |
| local cell | `u16` | owner cell: bits 0–3 X, 4–7 Z, 8–11 Y; high bits zero |
| flags | `u8` | bit 0 *owned*; bits 1–6 owner offset; bit 7 zero |
| provenance kind | `u8` | 0 brush side, 1 displacement triangle, 2 BSP draw face |
| material ID | `u32` | index into the map's material-reference table |
| UV-region ID | `u32` | index into this file's UV table |
| provenance primary | `u32` | brush, displacement or BSP face index |
| provenance secondary | `u32` | side, triangle, or piece index within the face |
| vertex count | `u8` | 3–64 |
| vertices | count x 3 `u16` | X, Y, Z within the owner cell, in 1/4096 block, 0–4096 |

A fragment is one planar convex polygon, the part of a Source face that lies
inside its owner cell. Its vertices are wound counter-clockwise seen from the
front: `(v1 - v0) x (v2 - v0)` points out of the visible side. The absolute
position of a vertex is `cell + coordinate / 4096`; a vertex on a shared cell
boundary is written as exactly 0 or 4096 in each cell, so fragments meeting at a
cell boundary share their edge bit-for-bit.

The fragment's cell is the one behind it, on the solid side of the face. A
fragment lying exactly in a cell boundary plane belongs to the cell behind that
plane, never to the one in front. Records are bucketed and positioned by this
cell.

*Owned* means the fragment has an owner block: an `src2mc:surface` block in the
map's schematic at the owner cell, which is the fragment's cell plus the owner
offset. The block is the fragment's editable handle: while the owner cell holds
an `src2mc:surface` block the fragment is drawn, and once the block is broken or
replaced it is not. The offset is zero when the fragment's own cell holds the
block. It is one cell further behind, along the axis the normal points most
along, when the face sits just off the grid — a floor a quarter of a block above
the block it rests on. Bits 1–2, 3–4 and 5–6 hold the X, Y and Z offsets plus
one, each 0, 1 or 2; 3 is invalid, and a fragment that is not owned has a zero
offset (bits 1–6 = `0b010101`).

A fragment that is not owned has no block near enough behind it — thin brushes,
and faces whose solid side fills too little of the cells behind it to
voxelize — and is drawn unconditionally.

Records within a section are ordered by local cell, provenance kind,
provenance primary, then provenance secondary, and that key is unique. `u32`
references and counts avoid a campaign-wide `u16` ceiling; loaders must
additionally enforce the defensive allocation limits in section 10, where the
face limit counts fragments.

## 6. Campaign and map metadata

`campaign.json` is canonical JSON with `format`
`src2mc-campaign-metadata`, `version` 1, the same `campaign_id` as the
manifest, and a `maps` array. Each map item contains its `map_id` and canonical
metadata path `maps/<map-id>.json`. Map items are sorted by map ID and IDs are
unique. Campaign and map IDs consist only of lowercase ASCII letters, digits,
`_` and `-` so they are portable path and Minecraft resource-path components.
Display/source names are separate and may contain other UTF-8 characters.

Each `maps/<map-id>.json` has `format` `src2mc-map`, `version` 1, and these
fields in order:

| Field | Meaning |
| --- | --- |
| `map_id` | stable ID matching the entry name |
| `source_name` | human-readable Source map provenance; not identity |
| `units_per_block` | finite positive conversion scale; 32 for mod export |
| `cell_min`, `cell_max` | inclusive map-local schematic cell bounds |
| `anchor_cell` | map-local storage cell of the authoritative anchor |
| `surfaces` | canonical `maps/<map-id>/surfaces.s2faces` path |
| `materials` | map-local material-reference array |
| `models` | unique model-reference array sorted by content ID, source path, then material IDs |
| `props` | canonical `maps/<map-id>/props.s2props` path |
| `pvs` | optional canonical `maps/<map-id>/pvs.s2pvs` path; absent when the map has no usable PVS |
| `occlusion` | optional canonical `maps/<map-id>/occlusion.s2occl` path (section 13) |
| `collision` | optional canonical `maps/<map-id>/collision.s2coll` path (section 14) |
| `audio` | optional canonical `maps/<map-id>/audio.json` path (section 15) |
| `logic` | optional canonical `maps/<map-id>/logic.json` path (section 16) |
| `movers` | optional canonical `maps/<map-id>/movers.json` path (section 17); absent when the map has no movers |
| `logic_props` | optional canonical `maps/<map-id>/logic_props.json` path (section 18); absent when the logic changes no prop |
| `diagnostics` | canonical `maps/<map-id>/diagnostics.json` path |

Surface-table material IDs index `materials` directly. This array therefore
retains the converter/BSP material order and is not sorted during encoding.
Each material record contains the normalized diagnostic `source_material`, an
optional differing `source_material_raw`, `render_class` (`solid`, `cutout`,
`translucent`, or `fallback`), an optional texture reference, optional Source
`surface_prop`, three finite reflectivity values, and `double_sided`, present
only as `true`, when the Source material sets `$nocull`. A double-sided
surface is drawn from both sides; the mod adds each triangle's mirror, wound
the other way and lit from the side it faces. Cutout takes precedence
when Source declares both alpha-test and translucency, matching Source's hard
alpha test rather than turning grates into blended panes.

A texture reference contains its 64-character lowercase SHA-256 logical-content ID
and positive `original_width`, `original_height`, `output_width`, and
`output_height`. Output dimensions record resampling rather than hiding it.
The optional campaign `atlas` field is exactly `atlas.json`; it is required when
any material has a texture. The logical ID is retained in `atlas.json`, while
pixels live only in content-addressed `atlas/<page-mip-content-id>.png` pages.
Thus the bundle does not duplicate a logical image as both standalone and atlas
payloads. `atlas.json` records the fixed 4096 page size, mip levels 0 through 4,
16-pixel base gutter, every page mip and its dimensions, and every logical
texture's lossless source-to-page rectangles. A model reference
similarly contains its content ID, diagnostic normalized Source model path,
a `materials` array mapping mesh material slots to map-local material IDs, an
optional `color`, and an optional lowercase `surface_prop`, the model's
`$surfaceprop`. `color` is the `[r, g, b]` tint, each 0 to 255, the model is
drawn with: every texel's colour is multiplied by it, as Source's colour
modulation does. It is absent for white, which is no tint, and never written
as `[255, 255, 255]`. A `prop_static` takes it from the static prop lump's
diffuse modulation (Hammer's `rendercolor`; read for lump versions 7 to 9,
whose record keeps it at byte 64; other versions are drawn white), an entity
prop from its `rendercolor` keyvalue, read as `UTIL_StringToColor32` reads it:
up to three integers, missing ones 0, each kept to its low byte.
Model bytes live at `meshes/<content-id>.s2mesh`.

Multiple model references may name the same content ID when their Source-model
provenance, map-local material-slot arrays or tints differ. References are
uniquely sorted by `(content_id, source_model, materials, color)`, a reference
without `color` before every one with it and colours by red, green, then blue;
prop placement records select the reference index. The mesh payload still occurs only once. This is how a
Source skin family is carried: props of one model wearing different skins share
the mesh and each select a reference whose `materials` are that skin's. A skin
the model does not have is exported as the default skin, as Source draws it.

`maps/<map-id>/diagnostics.json` has format `src2mc-diagnostics`, version 1,
and a canonically sorted `messages` array. Each message has severity `info`,
`warning`, or `error`; a stable uppercase ASCII/digit/underscore code; a human
message; and a lexicographically keyed string context map. Human wording is
not a programmatic error code. An empty message array is valid.

## 7. Runtime prop mesh

Each deduplicated model mesh is `meshes/<content-id>.s2mesh`. Its little-endian
header contains the 8-byte magic `S2MESH\0\0`; `u32` version 1; `u32` vertex,
index, and submesh counts; then minimum and maximum bounds as six finite `f32`
values. Positions and bounds are model-local Minecraft block coordinates.

Each vertex is eight `f32` values: XYZ position, XYZ normal, and UV. Values are
finite, signed zero is normalized, and normals must be nonzero. The vertex
array is followed by `u32` triangle indices. Each following submesh contains
`u32` first-index, index-count, and material-slot fields. Ordered, contiguous,
nonempty submesh ranges cover the complete index buffer and contain whole
triangles. Material slots index the map model reference's `materials` array.
This preserves Source vertex normals; exporters must not silently replace them
with reconstructed flat triangle normals.

## 8. Prop placement table

Each map's roots are `maps/<map-id>/props.s2props`: magic `S2PROP\0\0`, `u32`
version 1, and `u32` record count, followed by fixed 112-byte records sorted by
stable ID:

| Field | Type | Meaning |
| --- | --- | --- |
| stable ID | 32 bytes | SHA-256-derived placement identity |
| model | `u32` | map model-reference index |
| root cell | 3 x `i32` | authoritative map-local storage cell |
| translation | 3 x `f64` | exact map-local visible model origin |
| rotation | 4 x `f64` | unit quaternion XYZW in Minecraft axes |
| scale | `f64` | finite positive uniform scale |

Stable IDs are unique and independent of nearest-free root-cell selection.
Moving the root therefore never moves the visible mesh or changes placement
identity. Signed-zero transform components are normalized when encoded.

## 9. Schematic anchor and prop-root NBT

Mod export writes Sponge schematic version 3. The complete map is one
schematic and its generic surface blocks use `src2mc:surface`. Its authoritative
map anchor and prop roots use `src2mc:map_anchor` and `src2mc:prop_root`.
The schematic bounds extend one cell below the converted map and the anchor is
placed at that new minimum-Y corner. It is therefore relative position
`[0,0,0]`, while never replacing map geometry.
`Blocks.BlockEntities` is a list of compounds in lexicographic XYZ position
order. Each compound uses WorldEdit 7.3.8's v3 envelope:

- `Id`: the namespaced block-entity type string;
- `Pos`: three `int`s relative to the schematic minimum, not world position;
- `Data`: the block entity's persistent payload.

Anchor `Data` contains `schema_version` int 1, `campaign_id`, `map_id`, and
`anchor_cell` as three map-local ints. The anchor cell is also recorded in map
metadata. This intentional redundancy lets validation reject a mismatched
schematic and lets the pasted world position establish the one supported
translation from map-local to world coordinates.

Prop-root `Data` contains `schema_version` int 1, `campaign_id`, `map_id`, the
64-lowercase-hex `stable_id`, `model_content_id`, map-local `root_cell`, exact
map-local `translation` as three doubles, unit-quaternion `rotation` as four
doubles in Minecraft XYZW axes, positive double `scale`, map-local
`material_ids` as an int array, and diagnostic `source_model`. Doubles are
finite and signed zero is canonicalized. The visible world transform is
derived from the pasted root position and `translation - root_cell`; moving or
copying a root therefore preserves the model's offset from its storage cell.
The bundle placement table remains the canonical batch copy of the same data.

Duplicate block-entity positions, positions outside the schematic bounds,
invalid identifiers, invalid references, non-finite transforms and non-unit
quaternions fail export. An anchor/root block and its matching block entity are
both required. Legacy display entities are never emitted by mod export.

## 10. Defensive limits and error codes

All counts and sizes are checked before allocating their complete payload.
These are hard corruption guards, distinct from configurable client RAM/VRAM
residency budgets:

| Resource | v1 maximum |
| --- | ---: |
| ZIP entries | 100,000 |
| entry path | 240 UTF-8 bytes |
| manifest bootstrap payload | 64 MiB |
| maps | 4,096 |
| JSON nesting | 64 |
| materials per map | 1,000,000 |
| models per campaign | 1,000,000 |
| props per map | 10,000,000 |
| UV regions per map | 10,000,000 |
| nonempty sections per map | 4,000,000 |
| face records per map | 100,000,000 |
| vertices / indices / submeshes per mesh | 10,000,000 / 30,000,000 / 65,536 |
| original texture axis | 16,384 |
| output texture axis | 4,096 |
| decoded bytes per texture | 256 MiB |
| PVS clusters per map | 65,536 |
| PVS leaves per map | 4,000,000 |
| PVS bitset payload | 256 MiB |
| movers per map | 16,384 |
| cells per mover axis (`size`) | 4,096 |
| blocks per mover, surface and carrier together | 1,000,000 |
| props per mover | 65,536 |
| logic props per map | 16,384 |
| skin families per logic prop | 1,024 |
| uncompressed entry | 2 GiB |
| uncompressed bundle payload | 64 GiB |
| ZIP expansion ratio | 200:1 after a 1 MiB small-entry allowance |
| total runtime allocation | 16 GiB |

The reader additionally detects integer overflow and verifies that accumulated
sizes never exceed their enclosing entry. A lower configured runtime or
texture-residency budget may reject otherwise structurally valid content.

Stable machine error codes are `UNSAFE_PATH`, `LIMIT_EXCEEDED`,
`ZIP_EXPANSION_LIMIT`, `HASH_MISMATCH`, `MISSING_ENTRY`,
`UNSUPPORTED_VERSION`, `INVALID_SCHEMA`, `INVALID_REFERENCE`,
`DUPLICATE_IDENTITY`, `NO_FREE_PROP_ROOT`, and `SCHEMATIC_TOO_LARGE`.
Diagnostics may add context and human wording without changing these tokens.

## 11. Phase 1 export lock

The schemas above, single-map and batch `mod export` commands, deterministic
synthetic fixture, and local real-map stability check are complete. Texture
payload creation is owned by Phase 4. Mod export now resolves world materials,
records original and effective dimensions, and writes deduplicated logical PNG
payloads. Replacing those temporary standalone payloads with the final paged
representation remains an atomic format step so readers never accept an
unreferenced or half-migrated atlas.

## 12. Prop visibility table

`maps/<map-id>/pvs.s2pvs` optionally records the Source BSP visibility
clusters (PVS) that static-prop rendering uses to reject map regions the
camera cannot see. Map metadata references it through the optional `pvs`
field; a map without usable visibility data simply omits both, and readers
must render unfiltered in that case. The payload begins with this fixed
little-endian header:

| Field | Type | Value |
| --- | --- | --- |
| magic | 8 bytes | `S2PVIS\0\0` |
| version | `u32` | 1 |
| cluster count | `u32` | PVS rows and cluster IDs |
| row bytes | `u32` | `(cluster count + 7) / 8` |
| node count | `u32` | following BSP node records |
| root node | `i32` | world-model BSP root index |
| section count | `u32` | following section records |

BSP node records follow in original BSP node order. Each is 24 bytes: four
finite `f32` values `(normal_x, normal_y, normal_z, distance)` for the
map-local plane `normal · point = distance`, then two `i32` children. A
non-negative child is another node index. A negative child encodes a leaf
cluster as `-1 - cluster`; `i32::MIN` denotes a solid or outside leaf. The
runtime walks this tree for the camera point, selecting child zero for a
positive plane distance and child one for a negative distance. A point on a
plane, invalid tree, or terminal solid leaf fails open. This exact traversal
is necessary: outward-rounded leaf AABBs overlap and cannot safely select a
camera cluster.

Section records follow in lexicographic order of their map-local 16-block
section coordinates: three `i32` values, a `u32` cluster count, and that many
ascending `u16` cluster IDs. Each record lists every leaf cluster whose
outward-rounded block-space box intersects the section box. Sections with an
empty set are omitted; a section absent from the table must fail open. The
converter derives the table from the transformed bounds of the map's props,
so every runtime prop section is covered.

The payload ends with `cluster count` PVS rows of `row bytes` each. Bit `c`
of row `k` (LSB-first within each byte) set means cluster `c` is potentially
visible from cluster `k`. The rows use the Quake II run-length scheme as
decoded from the BSP: a nonzero byte copies eight clusters, and a `0` byte
followed by a count skips that many zero bytes. An aggregate batch whose
section intersects at least one visible cluster renders; otherwise it is
rejected for the frame. Every row must include its own cluster; a table that
violates this integrity invariant is invalid. A camera cluster of `-1`, a
missing tree, or any other unresolved lookup renders without rejection.

## 13. Light-occlusion mask

`maps/<map-id>/occlusion.s2occl` optionally records the map-local cells that
stop daylight. Map metadata references it through the optional `occlusion`
field, which sits between `pvs` and `diagnostics`; a map that omits it leaves
readers to take the world's light exactly as vanilla computes it.

The mask holds every block of the map's schematic, and every cell a brush drawn
as geometry instead of voxelized covers. The mod bakes the map's sky light from
the bundle alone, before the world's chunks are there to ask, so it cannot see
the schematic's blocks: listing only the blocks that own a visible surface left
the map's hidden mass open, and daylight seeped through it into the air pockets
hollowing leaves inside thick floors and walls. The drawn-brush cells are the
other half. Minecraft derives light opacity from the block state alone, so a
ceiling of thin plates would let daylight straight through; filling their cells
with invisible blocks would make them solid to walk into, so they are listed
here and the bake treats them as opaque.

Props are deliberately left out. A cell is the smallest shadow the mask can
express, and a prop that fills one — a tree, a conveyor, a pile of scrap — casts
a block of shade the thing itself never would.

The payload begins with this fixed little-endian header:

| Field | Type | Value |
| --- | --- | --- |
| magic | 8 bytes | `S2OCCL\0\0` |
| version | `u32` | 1 |
| section count | `u32` | following section records |

Section records follow in lexicographic order of their map-local 16-block
section coordinates: three `i32` values, then 512 bytes holding one bit per
cell. Bit `(y & 15) << 8 | (z & 15) << 4 | (x & 15)` — the surface table's
local-cell packing — is set when that cell blocks light, LSB-first within each
byte. Every listed section has at least one bit set, and coordinates are unique
and ascending, so the payload is canonical for a given cell set.

The mask is advisory for rendering and authoritative for nothing else: it never
adds collision or blocks, and a reader that ignores it produces a correct but
over-lit world.

## 14. Collision table

`maps/<map-id>/collision.s2coll` optionally records what the map's cells
collide as. Map metadata references it through the optional `collision` field,
which sits between `occlusion` and `diagnostics`. A map that omits it collides
as it always did: every `src2mc:surface` block a full cube.

With the table, a `src2mc:surface` block with no entry is a full cube and one
with an entry collides as that entry's shape, which may be empty. A
`src2mc:carrier` block collides as its entry and as nothing without one.
Carriers are placed by the schematic in cells that hold solid volume but no map
block; they block no light, cull no faces, and are replaceable.

Shapes come from the same geometry as the surfaces (D17, D18): per cell, the
volume of the converted brushes and the displacement terrain inside it, rounded
outward to sixteenths of a block. A piece of volume in a cell without a block
hangs off the face-adjacent map block it touches most, so a shape may reach one
cell beyond its own in any direction; a piece with nothing to touch, and every
brush thinner than the brush-mesh cut-off, gets a carrier instead. Cells that
hollowing emptied are sealed inside the map and get neither.

Solid props add their volume to the same cells: into a map block's shape, or
into a carrier's. A `src2mc:prop_root` placed in such a cell collides as the
cell's entry, as a carrier would, and the schematic then has no carrier there.

The payload is little-endian:

| Field | Type | Value |
| --- | --- | --- |
| magic | 8 bytes | `S2COLL\0\0` |
| version | `u32` | 1 |
| shape count | `u32` | following shape records |

Each shape record is a `u16` box count followed by that many boxes of six
`i8` values, `x1 y1 z1 x2 y2 z2`, in sixteenths of the carrying cell. Every box
has `x1 < x2`, `y1 < y2`, `z1 < z2`, and every value lies in -16..32. Shapes
are unique and sorted: box by box, each box by its signed bytes, and a shape
that is a prefix of another first. The empty shape, when used, is therefore
shape 0.

Then a `u32` section count and the section records, in lexicographic order of
their map-local 16-block section coordinates: three `i32` values, a `u16` cell
count of at least 1, and that many cell records of a `u16` local index —
`(y & 15) << 8 | (z & 15) << 4 | (x & 15)`, strictly ascending — and a `u32`
shape index. Nothing may follow the last section.

## 15. Sound table

`maps/<map-id>/audio.json` optionally records the map's sound. Map metadata
references it through the optional `audio` field, which sits between
`collision` and `diagnostics`. It holds every `env_soundscape` and
`ambient_generic` whose sound resolves, the soundscripts each surface property
plays, and the soundscripts the map's logic plays by name: those of scene
`speak` events (section 16), `infra_music` entities' `sound`, and the sounds
buttons and doors name in their keyvalues (`noise1`, `locked_sound`,
`soundopenoverride` and the like, and a button's `sounds` number as
`Buttons.snd<n>`). Which of the sound
entities play from the start is the mod's decision from their flags: Source
starts an `ambient_generic` at spawn exactly when it neither starts silent
(spawnflag 16) nor is flagged not-looping (32), and an `env_soundscape` unless
it starts disabled. Everything else waits for the map's logic.

The payload is canonical JSON with `format` `src2mc-audio`, `version` 2, and
these arrays in order: `sounds`, `soundscapes`, `emitters`, `ambients`,
`scripts`, `surfaces`. Indices refer into these arrays. A *range* is an array
of two finite numbers, Source's interval: a value is drawn uniformly between
them each time it is used. Positions are map-local blocks; sound levels are
Source decibels, 0 meaning heard everywhere without falloff; pitch is
Source's, 100 being unchanged. An `entity` is the entity's index in the BSP
entity lump, which is also its index in the logic table's `entities`.

| Record | Fields, in order |
| --- | --- |
| sound | `content_id`; diagnostic `source` path below `sound/`; `channels` 1 or 2; `sample_rate`; `frames`; optional `loop_start` frame, less than `frames`, present only for a sound that loops |
| soundscape | `name`; `loops`; `randoms`; `children` |
| loop | `sound`; `volume`, `pitch`, `sound_level` ranges; optional `position` index |
| random | non-empty `sounds`; `time`, `volume`, `pitch`, `sound_level` ranges; optional `position`; `random_position` boolean |
| child | `soundscape`; `volume` range; `position` offset; optional `position_override`, `ambient_position_override` |
| emitter | `entity`; `position`; `radius` in blocks, -1 for unlimited; `soundscape`; `positions`, exactly eight entries, each a position or `null`; `start_disabled` boolean |
| ambient | `entity`; `position`; non-empty `sounds`; `volume`, `pitch`, `sound_level` ranges; `flags`, the entity's spawnflags |
| script | unique lowercase `name`; non-empty `sounds`; `volume`, `pitch`, `sound_level` ranges |

Scripts also hold the sounds entity keyvalues name, keyed by the script's
name or, for a sound file, by its lowercase path below `sound/` with `/`
separators and without leading sound characters, at volume 1, pitch 100 and
sound level 75, as Source plays a bare file.
| surface | unique lowercase `name`; optional `step_left`, `step_right`, `impact_soft`, `impact_hard`, `break_sound` script indices |

Commands keep Source's meaning (`c_soundscape.cpp`): a loop or random sound
without a position is heard everywhere; `position` indices are offset by the
enclosing children's `position` and replaced by their overrides. Surfaces
include `default`, which a `$surfaceprop` the table does not list falls back
to. Material and model `surface_prop` values select surfaces.

Each sound's payload is `audio/<content-id>.ogg`: Ogg Vorbis, mono or stereo,
whose decoded frame count is `frames`. The converter encodes at Vorbis quality
7 with a fixed stream serial, so equal sounds have equal IDs. Stereo files
Source places in the world as one point (`)` or `(` before the name) and
files with more than two channels are mixed down to mono; other stereo files
stay stereo, which Source plays unpanned. Whether a sound loops comes from
the WAV's first `cue ` point, or its first `smpl` loop: Source loops a file
from that frame to its end, and plays a file with neither once.

## 16. Logic table

`maps/<map-id>/logic.json` optionally records the map's entities for the
mod's logic runtime (Source's entity I/O). Map metadata references it through
the optional `logic` field, which sits between `audio` and `diagnostics`.

It is deliberately complete rather than a subset the current runtime uses:
every entity of the BSP entity lump with every keyvalue as written, so later
features (VScript, lights, movers) read the map's own data rather than
needing a new export.

The payload is canonical JSON with `format` `src2mc-logic`, `version` 1, and
these fields in order:

| Field | Meaning |
| --- | --- |
| `source_origin` | the map-local block position of Source's origin. A map-local point `(x, y, z)` is Source's `((x - ox) * 32, -(z - oz) * 32, (y - oy) * 32)` |
| `entities` | every entity of the lump, in lump order, worldspawn first |
| `volumes` | brush-entity shapes, referenced by entities |
| `scenes` | parsed choreography scenes, referenced by entities |
| `captions` | closed-caption texts |
| `strings` | optional; localized texts of the `#` tokens the map's HUD texts name |
| `engine_entities` | optional; entities the game's own code creates in the map |
| `engine_events` | optional; inputs the game's own code queues as the map spawns |

| Record | Fields, in order |
| --- | --- |
| entity | `classname`, lowercase; `keyvalues`, an array of `[key, value]` string pairs in lump order, keys as written, outputs excluded; `outputs`; optional `origin`, map-local position of the `origin` keyvalue; optional `volume` index; optional `scene` index |
| output | `output`, the output's name as written (`OnTrigger`); `target`; `input`; `parameter`; `delay` in seconds, finite and not negative; `times`, -1 for unlimited or at least 1 |
| volume | `bounds`, map-local `[min x, min y, min z, max x, max y, max z]`; non-empty `brushes` |
| brush | non-empty array of planes `[nx, ny, nz, d]` in map-local blocks with unit outward normals: a point `p` is inside the brush when `n . p <= d` for every plane |
| scene | `file`, the scene path as the entity names it; `length` in seconds; `events` in start order |
| speak event | `type` `speak`; `actor`, lowercase; `start`, `end` in seconds; `script`, the lowercase soundscript name; optional `caption`, the caption token, lowercase |
| firetrigger event | `type` `firetrigger`; `start` in seconds; `trigger`, 1 to 16, the `OnTrigger<n>` output it fires |
| caption | unique lowercase `token`; `text`, as the caption file writes it, tags included |
| string | as a caption: unique lowercase `token` without its `#`, `text` |
| engine event | `target`, `input`, `parameter`; `delay` in seconds, finite and not negative |

Keys are matched as Source matches them, ignoring ASCII case, and when a
key repeats, the last occurrence is the entity's value, as Source applies the
pairs in order. An output is a
keyvalue whose key starts with `On` (any case) and whose value splits into
five fields on `0x1B`, or for older maps on `,`: target, input, parameter,
delay, times. Repeated outputs are all kept, in lump order. Delay and times
are read as Source reads them (`atof`, `atoi`); a negative delay becomes 0,
and times of 0 or below become -1, since such an output never counts down to
removal. Any other `On` key with a separator, which does not make five fields
or has a non-finite delay, stays an ordinary keyvalue and is counted in a
`LOGIC_OUTPUT_MALFORMED` diagnostic.

A brush entity whose `model` is `*<n>` has a `volume`: all brushes of BSP
model `n`, moved to the entity's `origin` exactly as the drawn geometry is
(VBSP stores a brush model relative to it), then mapped to map-local blocks.
The entity's `angles` are not applied, again as for the drawn geometry; a
rotating entity's angles are its runtime rotation, left to the runtime. Every brush entity gets
one, whether it is a trigger, a button, a door or `func_brush`, drawn or not,
as long as at least one of its brushes encloses a volume. `bounds` is the box
around the brushes' own vertices.

A `logic_choreographed_scene` (or `scripted_scene`, its older name) whose
`SceneFile` resolves to a text `.vcd`, in the map's pakfile or the game, has
a `scene`; entities naming the same file share it. Compiled scenes
(`scenes.image`) are not read, so a game that ships only those has no scenes,
and each missing file is a `LOGIC_SCENE_UNAVAILABLE` warning. Only `speak`
and `firetrigger` events are kept; scene `length` is the latest end or start
of any event in the file. An event whose time is not finite or beyond a day is
dropped, starts below 0 become 0, and a speak event's `end` is at least its
`start`. `script` is the event's parameter; `caption` is the `cctoken` when
it is not empty and otherwise the script name, absent for an event whose
`cctype` is `cc_disabled`, and present only when the captions name it.
`captions` holds the English captions of every script name in the sound
table and every scene caption, read from `resource/closecaption_english.txt`
and then `resource/subtitles_english.txt` (INFRA keeps its dialogue in the
latter), the first file to define a token winning. Escapes in the files'
strings (`\"`, `\\`, `\n`, `\t`) are resolved; keys starting with `[`
(`[english]` originals) are not captions.

`strings` holds the texts of the tokens a `game_text`'s `message`, a `SetText`
output parameter or an engine event's `SetText` parameter names with a leading
`#`, as `g_pVGuiLocalize->Find` gives the HUD: read from every loose
`resource/*_english.txt` of the search path, in path order, the first file to
define a token winning. A `#` token they lack, and any other text, is shown
as written. It is absent when no token resolves.

`engine_entities` are entity records like `entities`, for entities that are
in no lump but that the game's code creates in every map; the runtime gives
them the indices after the lump's, in order. `engine_events` are queued in
order when the map spawns (not when a save is restored), as `EntFire` would:
each with no activator or caller. Both are absent when empty. Today only
INFRA has them: its `server.dll` creates the `game_text`s
`@chapter_title_text` and `@chapter_subtitle_text` (message `chapter_title`
and `chapter_subtitle`, colours `255 255 255` and `205 205 205`; the other
keyvalues, scan-out effect 2, `fadein` .06, `fxtime` .5, `holdtime` 5,
`fadeout` .5, channels 2 and 3 and heights .55 and .6, are Portal 2's
identical entities of the same names) and runs
`scripts/vscripts/chapter_titles.nut`. A map with a row in its
`CHAPTER_TITLES` gets both entities and the script's `DisplayChapterTitle`
calls: `SetTextColor` `210 210 210 128`, `SetTextColor2` `50 90 116 255`,
`SetPosY` .32 and .35, `SetText` the row's tokens, and, when the row displays
on spawn, `Display` at its delay and `Kill` 5.6 seconds later.

## 17. Mover table

`maps/<map-id>/movers.json` optionally records the map's moving entities, for
the mod to move each one as its own small map (a physics sub-level). Map
metadata references it through the optional `movers` field, which sits between
`logic` and `diagnostics`. It is absent when the map has no movers.

A *mover* is a brush entity (`model` `*<n>`) of class `func_door`,
`func_door_rotating`, `func_movelinear`, `func_tracktrain`, `func_rotating`,
`func_button`, `func_rot_button`, `momentary_rot_button`, `infra_button`,
`func_brush` or `func_wall_toggle`, or a `prop_door_rotating`, a door that is
a model rather than brushes. A mover is entirely absent from the static map:
its brushes make no `src2mc:surface` blocks, carriers or collision in the map
schematic and collision table, its faces are not in the map's surface table,
its brushes add nothing to the light-occlusion mask, and the props riding on
it are not in `props.s2props` and add nothing to the map's collision.

A prop rides on a mover when its `parentname`, without any `,attachment`
suffix and matched ignoring ASCII case against `targetname`, names the mover,
or names an entity that itself rides on one, through any chain of parents. A
`prop_door_rotating` carries its own model. When several entities share the
parent's name, the first mover among them in lump order is taken, failing that
the first whose own chain reaches a mover, and a `MOVER_PARENT_AMBIGUOUS`
warning lists the names. A mover's own `parentname` does not merge it into
another mover; each is its own record, and the logic table keeps the parent.

The payload is canonical JSON with `format` `src2mc-movers`, `version` 1, and
`movers`, an array in entity lump order with unique entities. Each mover
record has these fields in order:

| Field | Meaning |
| --- | --- |
| `entity` | the entity's index in the BSP entity lump, as in the logic table's `entities` (worldspawn is 0) |
| `classname` | lowercase |
| `cell_origin` | map-local `[x, y, z]` cell of mover-local cell `[0, 0, 0]`, in the pose the map spawns the entity in |
| `size` | `[sx, sy, sz]`, each 1 to 4,096: every mover-local cell the record uses lies in `[0, size)` |
| `surfaces` | optional `maps/<map-id>/movers/<entity>.s2faces`; absent when the mover has no visible face |
| `collision` | optional `maps/<map-id>/movers/<entity>.s2coll`; absent when no cell has a shape |
| `blocks` | `{"surface": [...], "carrier": [...]}`, mover-local `[x, y, z]` cells |
| `props` | the riding props, in lump order |

| Record | Fields, in order |
| --- | --- |
| prop | `entity`, the prop's lump index; `model`, an index into the map's model-reference table; `translation`, mover-local block coordinates of the model origin; `rotation`, unit quaternion XYZW in Minecraft axes; `scale`, finite and positive; `skin`, the Source skin, already reflected in the model reference's `materials` |

A mover-local position is the map-local position minus `cell_origin`; the
mod moves the whole mover by transforming mover-local space. The logic table
is unchanged: a mover's `volume` and `origin` stay map-local in the spawn
pose. `cell_origin` is the componentwise minimum over the mover's blocks and
carriers, the cells its fragments lie in and their owner cells, every cell a
collision shape reaches into (a hanging piece reaches one cell beyond its
owner), and for each prop the cells of its transformed model bounding box and
the cell holding its origin. Every coordinate in the record is therefore at
least 0, a prop's `translation` included. A mover with none of these — an
invisible, non-solid brush entity with no props, such as most buttons, whose
model is a separate prop — has nothing to move and is left out of the table
(it still has its logic-table entity and volume); a `MOVER_SUMMARY` info
diagnostic counts exported, block-less and left-out movers, riding props and
ambiguous parent names.

The mover's geometry is built exactly as the map's (sections 5 and 14), from
its own brushes alone: brushes thinner than the brush-mesh cut-off are not
voxelized, the rest become the mover's own voxel grid, the face lump's faces
are cut per cell against that grid, and collision is computed against it, so a
fragment's owner and a hanging collision piece's owner are one of the mover's
own blocks. Nothing is hollowed. A mover built only of thin brushes, such as
a door panel, has an empty `surface` list: its fragments are all unowned and
its collision lives in carriers. Riding props add their volume to the mover's
collision by the rules of section 14.

`blocks.surface` is the mover's voxel grid: each cell holds an
`src2mc:surface` block. `blocks.carrier` is the collision table's carriers:
each cell holds an `src2mc:carrier` block. Both lists are strictly ascending
by X, then Y, then Z, lie inside `size`, and are disjoint. Unlike the map
schematic, no cell is reserved for prop roots; where a prop's root block goes
in the sub-level is the mod's choice.

The surface payload is exactly the section 5 S2FACE v2 format with every
coordinate mover-local: cells, sections and owner cells, and the UV regions'
offsets, which are rewritten so that `s` and `t` at a mover-local point equal
their map-local values at the same point in the spawn pose. Material IDs index
the map's own material-reference table, and textures share the map's atlas.
The collision payload is exactly the section 14 S2COLL format with mover-local
cells and sections. A riding prop's model and materials are exported with the
map's models whether or not anything in `props.s2props` uses them.

Limits are in section 10. Each mover adds at most two bundle entries, which
count against the ZIP entry limit.

## 18. Logic prop table

`maps/<map-id>/logic_props.json` optionally records the props the map's logic
changes, so the mod can draw and collide each of them on its own while every
other prop stays merged. Map metadata references it through the optional
`logic_props` field, which sits between `movers` and `diagnostics`. It is
absent when there are none.

A *logic prop* is an entity of the lump with a `.mdl` model that some output
of the map sends `Skin`, `Color`, `Enable`, `Disable`, `TurnOn`, `TurnOff`,
`Kill`, `KillHierarchy`, `DisableCollision` or `EnableCollision`, or that is a
`prop_dynamic`, `prop_dynamic_override` or `prop_dynamic_ornament` with a
non-zero `StartDisabled`. Targets resolve as `CEventQueue::ServiceEvents`
resolves them, ignoring ASCII case: every entity whose `targetname` matches, a
trailing `*` matching every name it begins; failing any, every entity of that
classname. Targets starting with `!` depend on who fires and are not followed.
Removing an entity removes its children (`UpdateOnRemove`), so every entity
whose `parentname` chain leads to a `Kill` or `KillHierarchy` target counts as
one too. A logic prop is still a placed prop in `props.s2props`, or a riding
prop of its mover in `movers.json`; this table adds to it.

The payload is canonical JSON with `format` `src2mc-logic-props`, `version` 1,
and `props`, an array in strictly ascending entity order. Each record has
these fields in order:

| Field | Meaning |
| --- | --- |
| `entity` | the prop's index in the BSP entity lump |
| `stable_id` | 64 lowercase hex digits, the `props.s2props` record of a placed prop; absent for a riding prop |
| `mover` | the entity of the mover whose `props` hold it; absent for a placed prop |
| `skins` | non-empty array of model-reference indices, one per skin family of the model, family 0 first; each reference wears that family's materials and the prop's tint |
| `skin` | the skin the map spawns it with, as written; a skin the model lacks is drawn as family 0 |
| `start_hidden` | present, and `true`, when the prop spawns hidden (`StartDisabled` on a dynamic prop) |
| `collision` | optional `maps/<map-id>/logic_props/<entity>.s2coll`; only for a placed prop |

Exactly one of `stable_id` and `mover` is present. A prop that some input
removes or switches the collision of (`Kill`, `KillHierarchy`,
`DisableCollision`, `EnableCollision`, or a removed ancestor) and is solid by
the rules of section 14 keeps its volume out of the map's collision table: its
cells are the section 14 S2COLL format of its own, map-local, and the mod adds
each cell's shape to the cell's own while the prop is solid. Its cells follow
the same rules as the merged ones: a cell whose map block is a full cube needs
nothing, and a cell with no map block gets an `src2mc:carrier`, which collides
as nothing while the prop is gone. A riding prop's collision stays merged in
its mover's.

The logic sets each logic prop's state, which the server sends its clients:
hidden (`EF_NODRAW`, or removed), not solid (`FSOLID_NOT_SOLID`, or removed),
the skin, and the tint. `Enable`, `Disable`, `TurnOn` and `TurnOff` only show
and hide a dynamic prop, which stays solid, as `CDynamicProp` does; `Skin`
reads its parameter as `atoi`, `Color` as `UTIL_StringToColor32`. A prop with
no state yet is as the map spawns it.

Each logic prop adds at most one bundle entry, which counts against the ZIP
entry limit.
