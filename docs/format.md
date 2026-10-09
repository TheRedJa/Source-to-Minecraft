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
| version | `u32` | 3 |
| UV-region count | `u32` | number of following UV records |
| light-region count | `u32` | number of following light-region records |
| section count | `u32` | number of following section buckets |
| fragment count | `u32` | total fragment records in all buckets |

UV regions follow in lexicographic order of their canonical IEEE-754 bit
patterns. Each consists of eight finite `f64` values: the four coefficients of
`s = ux*x + uy*y + uz*z + uoffset`, then the corresponding four coefficients
for `t`. Coordinates are map-local Minecraft block coordinates. Signed zero is
canonicalized to positive zero before deduplication and writing.

Light regions follow the UV regions, sorted by page, then by the canonical
bit patterns of their values, and unique. Each is a `u32` page index into the
map's light file (section 22) and eight finite `f64`: the same affine form,
giving the fragment's lightmap coordinates `s` and `t` as fractions of that
page's width and height. A coordinate lands on the centre of the luxel vrad
computed for that point of the face. A version 2 table, which has no light
regions and no light-region ID, is rejected.

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
| light-region ID | `u32` | index into this file's light-region table, or `0xFFFFFFFF` for none |
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
face limit counts fragments and the UV-region limit applies to the light
regions as well.

A fragment of a BSP draw face carries the region of its face's lightmap; so
does a displacement triangle, whose region is fitted through its three
corners' luxel coordinates. A face without a lightmap — vrad leaves none on
`NOLIGHT` surfaces such as black tool brushes, self-lit light panels and
fizzlers — and every brush-side fragment has none, and is drawn at full
brightness, as Source draws an unlit surface.

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
| `sky` | optional canonical `maps/<map-id>/sky.s2sky` path (section 20); absent when the map has no sky face |
| `skybox` | optional canonical `maps/<map-id>/skybox.s2box` path (section 21); absent without a 3D skybox room |
| `light` | optional canonical `maps/<map-id>/light.s2light` path (section 22) |
| `particles` | optional canonical `maps/<map-id>/particles.json` path (section 23) |
| `look` | optional canonical `maps/<map-id>/look.s2look` path (section 24) |
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
optional `color`, an optional `animation`, and an optional lowercase
`surface_prop`, the model's `$surfaceprop`. `animation` is the 64-character
content ID of the `animations/<content-id>.s2anim` (section 19) that poses an
animated prop's mesh; its vertex bindings are those of this reference's mesh. `color` is the `[r, g, b]` tint, each 0 to 255, the model is
drawn with: every texel's colour is multiplied by it, as Source's colour
modulation does. It is absent for white, which is no tint, and never written
as `[255, 255, 255]`. A `prop_static` takes it from the static prop lump's
diffuse modulation (Hammer's `rendercolor`; read for lump versions 7 to 9,
whose record keeps it at byte 64; other versions are drawn white), an entity
prop from its `rendercolor` keyvalue, read as `UTIL_StringToColor32` reads it:
up to three integers, missing ones 0, each kept to its low byte.
Model bytes live at `meshes/<content-id>.s2mesh`.

Multiple model references may name the same content ID when their Source-model
provenance, map-local material-slot arrays, tints or animations differ.
References are uniquely sorted by `(content_id, source_model, materials,
color, animation)`, a reference without `color` before every one with it and
colours by red, green, then blue, and one without `animation` before every one
with it;
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
| `.s2anim` payload | 64 MiB |
| bones / sequences / frames per sequence of an animation | 256 / 4,096 / 65,536 |
| light file | 1 GiB |
| light pages per map / page axis | 64 / 4,096 |
| ambient nodes / samples per map | 4,000,000 / 4,000,000 |
| prop light vertices per map | 100,000,000 |
| particle systems / particle materials per map | 65,536 / 65,536 |
| functions or children per particle system, decals per game material | 1,024 |
| sheet sequences per material / frames per sequence | 4,096 / 65,536 |
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

The mod no longer reads this mask: since DEV-0.36.0 the map is lit by its own
baked light (section 22, D28) and the sky light it fed is gone. The converter
still writes it.

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

The payload is canonical JSON with `format` `src2mc-audio`, `version` 3, and
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
| surface | unique lowercase `name`; optional `step_left`, `step_right`, `impact_soft`, `impact_hard`, `break_sound`, `bullet_impact` script indices; optional `game_material`, the one uppercase letter of the property's `gamematerial` (`CHAR_TEX_*`) |

Commands keep Source's meaning (`c_soundscape.cpp`): a loop or random sound
without a position is heard everywhere; `position` indices are offset by the
enclosing children's `position` and replaced by their overrides. Surfaces
include `default`, which a `$surfaceprop` the table does not list falls back
to. Material and model `surface_prop` values select surfaces. A bullet
impact plays the surface's `bullet_impact` script, and its `game_material`
picks the impact effect and decal (section 23); a surface without one counts
as concrete, `C`. A version 2 table, without these, is rejected.

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
collision by the rules of section 14, except a riding logic prop that keeps
collision of its own (section 18), whose cells only add carriers here. A brush of the mover with
solid contents but no drawn side (a `tools/toolsplayerclip` plate under a
conveyor belt prop) adds its volume to the collision too, through carriers,
when the mover is exported for anything else; an entity made only of such
brushes, such as an invisible use volume, is no mover. The map's own collision (section 14) takes the
world's such brushes, and its player clips, the same way, cut to the cells
the rest of the map fills.

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
`Kill`, `KillHierarchy`, `DisableCollision`, `EnableCollision`, `SetParent` or
`ClearParent`, or that is a
`prop_dynamic`, `prop_dynamic_override` or `prop_dynamic_ornament` with a
non-zero `StartDisabled`, or such a dynamic prop that animates: some output
sends it `SetAnimation`, `SetDefaultAnimation`, `SetPlaybackRate` or
`SetCycle`, or it has a `DefaultAnim` or a non-zero `RandomAnimation`, or that
a `point_template` names in `Template01` to `Template16` (a trailing `*`
matching every name it begins), which the mod copies at runtime; such a prop's
collision is kept apart as for a killed one. Targets resolve as `CEventQueue::ServiceEvents`
resolves them, ignoring ASCII case: every entity whose `targetname` matches, a
trailing `*` matching every name it begins; failing any, every entity of that
classname. Targets starting with `!` depend on who fires and are not followed.
Removing an entity removes its children (`UpdateOnRemove`), so every entity
whose `parentname` chain leads to a `Kill` or `KillHierarchy` target counts as
one too. A logic prop is still a placed prop in `props.s2props`, or a riding
prop of its mover in `movers.json`; this table adds to it.

The payload is canonical JSON with `format` `src2mc-logic-props`, `version` 3,
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
| `collision` | optional `maps/<map-id>/logic_props/<entity>.s2coll`; map-local for a placed prop, mover-local for a riding one |
| `sequence` | present for an animated prop (its models have `animation`): the sequence it spawns in |
| `poses` | optional non-empty array of `{sequence, collision}`, strictly ascending by sequence, `collision` being `maps/<map-id>/logic_props/<entity>_<sequence>.s2coll`; only with `collision` and `sequence` |

Exactly one of `stable_id` and `mover` is present. A prop that some input
removes or switches the collision of (`Kill`, `KillHierarchy`,
`DisableCollision`, `EnableCollision`, a removed ancestor, or `SetParent` and
`ClearParent`, which take it away from where it was compiled) and is solid by
the rules of section 14 keeps its volume out of the map's collision table: its
cells are the section 14 S2COLL format of its own, map-local, and the mod adds
each cell's shape to the cell's own while the prop is solid. Its cells follow
the same rules as the merged ones: a cell whose map block is a full cube needs
nothing, and a cell with no map block gets an `src2mc:carrier`, which collides
as nothing while the prop is gone. A riding prop's is the same in its mover's
cells (section 17): kept out of the mover's table, its cells in mover-local
coordinates, a cell with no mover block getting a carrier there, and the mod
adds it to the mover's cell shapes while the prop is solid. A prop parented
onto a mover at runtime collides there instead: the mod moves its table from
its compiled place into the carrying mover's cells and puts mover blocks where
it reaches.

A `point_template`'s entities leave the map as it spawns, unless its
spawnflag 1 keeps them: the mod spawns them neither shown nor solid, movers
included, and draws each copy a `ForceSpawn` makes from the same records -- a
copy of a prop wears the prop's model references, at its compiled place moved
as the logic moves the copy. The bundle has nothing per copy.

The logic sets each logic prop's state, which the server sends its clients:
hidden (`EF_NODRAW`, or removed), not solid (`FSOLID_NOT_SOLID`, or removed),
the skin, and the tint. `Enable`, `Disable`, `TurnOn` and `TurnOff` only show
and hide a dynamic prop, which stays solid, as `CDynamicProp` does; `Skin`
reads its parameter as `atoi`, `Color` as `UTIL_StringToColor32`. A prop with
no state yet is as the map spawns it.

An animated prop's model references are its reference pose, which the
`.s2anim` of their `animation` moves (section 19); its `collision` is the
volume of the pose it spawns in, the first frame of `sequence`, and each of
`poses` the volume of the pose that sequence leaves it in: its last frame, or
its first for one that loops. Every sequence the map can make the prop play
has one when the prop is solid. The mod adds the table of the pose the prop
stands in (the last sequence that settled; the spawn pose before any did) to
the cells while the prop is solid, the mover's cells for a riding prop.

The logic's state of an animated prop adds the sequence it plays, the cycle
at a map time, the rate it advances at (0 while the server does not advance
it), a parity that moves whenever Source would restart the sequence, and the
sequence whose settled pose its collision is in.

Each logic prop adds one bundle entry, and an animated prop one more per pose,
which count against the ZIP entry limit.

## 19. Animation

`animations/<content-id>.s2anim` holds the skeleton and sequences of an
animated prop's model, the content ID being the SHA-256 of the payload. A
model reference names it in `animation`. Everything is in the model-local
block space of the reference's runtime mesh (section 7): Source `(x, y, z)` is
`(x, z, -y)`, 32 units to the block, so a rotation keeps its angle and its axis
is mapped the same way. All values are little-endian; floats are finite.

The header is the 8-byte magic `S2ANIM\0\0`, `u32` version 1, and `u32` bone,
vertex, sequence and transition-node counts. The vertex count equals the
mesh's. Then:

- **Bones**, in order, each a parent after it: `i32` parent (-1 for a root),
  the rest position as three `f32`, the rest rotation as four `i16` (XYZW,
  each a fraction of 32767, normalized when read), and the bind transform,
  twelve `f32` row-major 3x4: model space to the bone's space in the pose the
  vertices were bound in, after undoing any turn the mesh was exported with.
- **Vertices**, one per mesh vertex: `u8` bone count 0 to 3, three `u8` bone
  indices, three `f32` weights. Used slots have a positive weight and a bone
  that exists; unused slots are zero.
- **Transitions**: `nodes * nodes` bytes, the model's transition graph; the
  byte at row `from - 1`, column `to - 1` is the node to go through.
- **Sequences**, in the model's order: the label and the activity name, each a
  `u16` byte length and UTF-8; `i32` activity weight; `u32` Source sequence
  flags (`0x1` looping, `0x2` snap, `0x100` real time); `f32` fade-in and
  fade-out seconds; `i32` entry node, exit node and node flags; `f32` cycles
  per second at playback rate 1 (`Studio_CPS` with every pose parameter at its
  spawn value); a `f32` weight per bone; `u32` frame count. A sequence the map
  cannot reach has 0 frames and nothing more; it keeps its name and timing so
  a lookup finds what Source would. Otherwise, per bone: a `u8` position track
  and a `u8` rotation track, each 0 (one value) or 1 (one per frame), then
  the positions, three `f32` each, then the rotations, four `i16` each.

A sequence's frames are the bones' local transforms as `CalcAnimation` decodes
the model (`.ani` animation blocks and sections included). Between two frames
a position is interpolated linearly and a rotation by `QuaternionBlend`; the
frame of cycle `c` is `c * (frames - 1)`. A sequence of several blended
animations is flattened at the spawn pose parameters. A bone's transform
composes its parents' (`BuildBoneChain`); a vertex is the weighted sum of its
bones' `bone-to-model * bind` applied to it, as the studio renderer skins it.

## 20. Sky

`maps/<map-id>/sky.s2sky` holds the faces Source draws its skybox through and
the six sides of the map's 2D skybox. Source never draws a sky face: the
engine draws the skybox around the eye first and the world over it, so a sky
face shows the skybox in the direction it is seen. The mod draws the faces
instead, each pixel sampling the side its view direction points at, which is
what a box of six textures at infinite distance shows.

Little-endian, after the magic `S2SKY\0\0\0` and `u32` version 1:

- **Sides**, six, in the order `rt`, `lf`, `bk`, `ft`, `up`, `dn` (Source's
  material suffixes): a `u8` 1 and the 32 bytes of the SHA-256 content ID of
  `sky/<content-id>.png`, or a `u8` 0 for a side the converter could not
  read, drawn black. PNG entries are stored, not deflated.
- **Faces**: `u32` count (at most 1,000,000), then per face a `u16` corner
  count, 3 to 256, and that many corners, three canonical `f32` each,
  map-local block coordinates. Each face is convex and wound
  counter-clockwise seen from the side the sky is drawn on; it is drawn from
  that side only.

A side is the material `skybox/<skyname><suffix>`, `skyname` being
worldspawn's keyvalue, as Source's LDR path draws it: `$basetexture` (the
`Sky` shader's HDR textures are not used), each texel multiplied by `$color`
(`{r g b}` in 0 to 255 or `[r g b]` in 0 to 1), and resampled through
`$basetexturetransform` (`center + rotate(scale * (uv - center)) +
translate`, clamped or wrapped per axis as the texture's `CLAMPS`/`CLAMPT`
flags say) so the image is the side as its quad shows it. An image is at
most 2048 texels along either axis. A material without `$basetexture` but
with `$color` is that colour.

Side `s` covers the directions whose largest Source component is its axis:
`rt` +X, `lf` -X, `bk` +Y, `ft` -Y, `up` +Z, `dn` -Z, Source axes, Minecraft's
`(x, y, z)` being Source's `(x, z, -y)`. With `d` the direction and `m` the
absolute value of its largest component, a side's `(s, t)`, each -1 to 1, is
`rt (-y, z)/m`, `lf (y, z)/m`, `bk (x, z)/m`, `ft (-x, z)/m`, `up (-y, -x)/m`,
`dn (-y, x)/m`, and its image is sampled at `u = (s + 1) / 2`, `v = (1 - t) /
2`, `v` 0 at the image's top row, filtered linearly and clamped to the edge.

The faces are the world model's faces with `SURF_SKY` set (`toolsskybox2d`
sets it with `SURF_SKY2D`), displacements excluded, less those wholly inside
the 3D skybox room when that is left out.

## 21. 3D skybox

`maps/<map-id>/skybox.s2box` holds the room the map's `sky_camera` stands in,
which Source renders behind the world from `sky_camera.origin + eye / scale`
with the player's view (`CSkyboxView::DrawInternal`), so it shows `scale`
times larger. It is written only when the room is left out of the
conversion. Little-endian, after the magic `S2BOX\0\0\0` and `u32` version 1:

- `f32` scale, positive; three `f64` the map-local block coordinates of the
  `sky_camera`; three `f64` those of Source's world origin.
- Fog: `u8` 0, or 1 followed by the colour as three `u8` (0 to 255, as
  written) and `f32` start, end and maximum density, in world units.
- `u32` cluster count and `(count + 7) / 8` bytes, bit `c` (LSB first) set
  when a leaf of PVS cluster `c` has `LEAF_FLAGS_SKY`: the room is drawn only
  for an eye in such a cluster, or in none, or when the count is 0.
- The lightmap page: `u32` width and height, then three `u16` per luxel,
  row by row, linear light times 4096.
- `u32` batch count; per batch a `u32` material ID (into the map's material
  table), `u8` lighting (0 lightmap, 1 vertex light), three `u8` tint (0 to
  255), `u32` vertex count, a multiple of 3, and that many vertices of ten
  canonical `f32`: position in blocks relative to the `sky_camera`, Minecraft
  axes; texture coordinates in repeats of the material's texture; lightmap
  page coordinates, 0 to 1; linear vertex light. Triangles are wound
  counter-clockwise from the side they are seen from.

The room's brush faces (world and brush entities wholly inside it, none
`NODRAW`, `SKIP`, `HINT`, `TRIGGER` or sky) and displacements are lit by their
lightmaps: the static style's first lightmap of the face record (`LUMP_FACES_HDR`
and `LUMP_LIGHTING_HDR` when the map has HDR light), luxel `ColorRGBExp32`
`value * 2^exponent / 255`. A face vertex's luxel coordinate is its lightmap
projection plus 0.5 minus the face's luxel minimum; a displacement's is its
grid fraction times the face's luxel size plus 0.5. Faces without a lightmap
use a white luxel. Static props (`sprp` entries inside the room) carry the
light vrad baked into `sp_hdr_<index>.vhv` (`sp_<index>.vhv` without HDR):
colour per LOD 0 hardware vertex, BGRA, linear `(colour * 2)^2.2`. A pixel is
`albedo^2.2 * tint^2.2 * light * exposure`, fogged as
`lerp(colour, fog^2.2, f^2)` with `f = min(max density, saturate((view depth
- start) / (end - start)))`, distances divided by the scale, and written
back to gamma.

## 22. Baked light

`maps/<map-id>/light.s2light` holds the light vrad baked into the map: the
lightmaps of its faces, the ambient light samples of its leaves, and the vertex
light of its static props. Map metadata references it through the optional
`light` field. Little-endian, after the magic `S2LITE\0\0` and `u32` version 1:

- `u32` page count, then per page `u32` width and height and that many luxels
  row by row, four bytes each, `ColorRGBExp32`: linear light
  `rgb * 2^exponent / 255`, the exponent a signed byte. Each face's static
  lightmap (the first style's first lightmap, `LUMP_FACES_HDR` and
  `LUMP_LIGHTING_HDR` when the map has HDR light) is copied in with a
  one-luxel border repeating its edge, so filtering never reads a neighbour.
  Pages are a power of two wide and at most 4,096 by 4,096.
- `u32` node count and `i32` root, then per node four `f32`, the map-local
  plane `normal · point = distance`, and two `i32` children: a node index, or
  `-1 - leaf`. This is the world model's tree; a point on or in front of a
  plane takes child 0.
- `u32` leaf count, then per leaf in BSP order `u32` first sample and `u32`
  sample count.
- `u32` sample count, then per sample 21 `f32`: the map-local position, then
  the light arriving from +X, -X, +Y, -Y, +Z, -Z in Minecraft axes, linear RGB
  each, `rgb * 2^exponent` as `CompressedLightCube` stores it. The samples are
  `LUMP_LEAF_AMBIENT_LIGHTING(_HDR)` with their index lump; a map storing one
  cube per leaf, in that lump alone or inside version 0 leaves, has it at the
  leaf's centre.
- `u32` prop count, equal to the placement table's, then per prop in its order
  `u32` vertex count and that many `(r, g, b, 0)` bytes: the colour vrad baked
  for each vertex of the prop's mesh, from `sp_hdr_<index>.vhv`
  (`sp_<index>.vhv` without HDR) by the mesh vertex's hardware vertex. Gamma
  space and half range, so linear `(colour * 2 / 255)^2.2`. A count of 0 means
  none; a count that differs from the mesh's vertex count is ignored.

A brush-face pixel is lit by its lightmap, a static prop vertex by its vertex
light. Anything else — a prop without vertex light, a logic or animated prop,
a prop riding a mover, an entity — is lit by the ambient cube at its centre:
the samples of the leaf the point is in, each weighted `1 / (d² + 1)` with
`d` in Source units, as noclip.website's `computeAmbientCubeFromLeaf` does; a leaf
without samples takes the nearest sample in the map. A normal `n` takes
`n.x² * cube[±X] + n.y² * cube[±Y] + n.z² * cube[±Z]`, the sides it faces. The
light is multiplied by the exposure, and Minecraft block light, linear, is
added on top; a pixel is `(albedo^2.2 * (light * exposure + block))^(1/2.2)`.

## 23. Particles, impacts and decals

`maps/<map-id>/particles.json` optionally records the map's particle effects,
how the game draws a bullet impact, and its bullet hole decals. Map metadata
references it through the optional `particles` field, after `light`. It is
canonical JSON with `format` `src2mc-particles`, `version` 1, and `systems`,
`materials`, `impacts`, `decals` in that order.

`systems` holds every `DmeParticleSystemDefinition` the map can start, from
the `.pcf` files `particles/particles_manifest.txt` and the map's own
`maps/<map>_particles.txt` list (binary DMX, encodings 2 to 5): the systems
`info_particle_system` entities name, their children, and the game's impact
systems. Positions and directions inside them stay in Source units and axes;
the mod converts at the map's `source_origin` (section 16).

| Record | Fields, in order |
| --- | --- |
| system | `name`; optional `material` index; `attributes`, every definition attribute by its `.pcf` name; `renderers`, `operators`, `initializers`, `emitters`, `forces`, `constraints`, arrays of functions; `children` |
| function | `function`, the `functionName`; `parameters`, every parameter by its `.pcf` name |
| child | `system` index; `delay` seconds |
| material | `name`, lowercase below `materials/` without `.vmt`; `material`, its index in the map's material table, whose texture it draws; lowercase `shader`; `additive`; `parameters`, every numeric `$` parameter by lowercase name; `sheet` |
| sequence | `clamp`; `frames`, each `[seconds, [[u0, v0, u1, v1], ...]]`, one rectangle per texture layer, as fractions of the texture |

A `.pcf` stores only values that differ from their defaults. The converter
fills in the rest from the defaults of Source SDK 2013's particle library
(`src/source/pcf_defaults.json`, read from `particles.a`), for the system,
for each function it knows and for the operator fade parameters every
operator has. A function the table does not know keeps only its stored
parameters, and the export reports `PARTICLE_FUNCTION_DEFAULTS_UNKNOWN`.
Values are JSON numbers, booleans, strings, or arrays of numbers for vectors,
colours (0 to 255) and matrices. `sheet` is the texture's VTF 7.3+ sheet
resource (tag `0x10 0 0`), indexed by sequence number, `null` for a number the
sheet skips; empty for a texture without one.

`impacts` holds `style`, `systems` and `materials`. Portal 2's BSP version (21)
and later draw a bullet impact with the `impact_*` system of the surface's
game material (`s_pImpactEffect`); their `style` is `systems` and `systems`
maps each game material letter to a system index. Older maps (Portal,
HL2-era, INFRA) draw the SDK's code effects (`FX_DebrisFlecks`,
`FX_DustImpact`, `FX_MetalSpark` and friends, `cl_new_impact_effects 0`);
their `style` is `code`. `materials` maps each material the SDK's code
effects use by name to a material index: for the code impacts, and on any map
with an `env_spark`, which every game draws with `FX_ElectricSpark`; it is
empty otherwise. A particle or decal texture is scaled down, keeping its
aspect ratio, to fit one atlas region (4,064 texels per axis), so it is never
split across pages.

`decals` maps a game material letter to the decals a bullet leaves on it,
from `scripts/decals_subrect.txt` (or `decals.txt`): the letter's
`TranslationData` entry names an `Impact.*` group, whose entries list
materials with weights. Each decal is `material` (index into `materials`),
`rect` `[u0, v0, u1, v1]` (the subrect material's `$Pos` and `$Size` over its
`$Material`'s texture, or the whole texture), `size` `[width, height]` in
Source units (texels times `$decalscale`) and `weight`. A map whose game has
no decal script, or a letter without one, has none.

A decal is a square centred on the hit, laid out in the surface's texture
space as the engine's `R_DecalComputeBasis` does without an S axis: on a wall
T points straight down and S = N x T; on a floor or ceiling (|N.z| > sin 45°)
S points along +X. It is clipped to each map fragment in the hit surface's
plane that it covers. A `decalmodulate` decal multiplies the colour behind it
by twice its own; any other draws alpha-blended and lit by the surface's
lightmap. The 2,048 newest decals stay (`r_decals`).

## 24. Look

`maps/<map-id>/look.s2look` holds what the mod needs to draw the map's view as
the game's post-processing does: whether the map has HDR light, how the game
blooms, the game's vignette, and the map's colour correction lookups. Map
metadata references it through the optional `look` field, after `particles`.
Little-endian, after the magic `S2LOOK\0\0` and `u32` version 1:

- `u8` flags: 1 the map has HDR light (`LUMP_LIGHTING_HDR`), which turns on
  auto exposure and bloom; 2 the game blooms as the later branch (Alien Swarm,
  Portal 2, INFRA) does, its `dev/downsample_non_hdr` material having
  `$bloomtype`; 4 a vignette follows. At most 7.
- `u8` bloom type, the material's `$bloomtype` (0 or 1); 0 without flag 2.
- `u8` x and `u8` y blur kernels, `$kernel` of `dev/blurfilterx_nohdr` and
  `dev/blurfiltery_nohdr`, 0 to 4; 0 is the SDK 2013 cross filter.
- With flag 4: `u32` width and height, at most 4,096, then that many bytes,
  row by row, of the red channel of the game's vignette texture
  (`$internal_vignettetexture` of `dev/engine_post`, default `dev/vignette`).
  Present only when `dev/engine_post` sets `$vignetteenable` and no ConVar
  proxy turns it on and off.
- `u32` lookup count, at most 256, then per lookup sorted by name `u32` name
  length, the name, and 98,304 bytes: a 32 × 32 × 32 RGB lookup, red fastest,
  then green, then blue, as the game's `.raw` file stores it. The name is the
  `filename` of a `color_correction` or `color_correction_volume` trimmed,
  lowercased and with `/` separators, which is how the entity names it.
  Names are unique.

A map without HDR light, colour correction or vignette still has the file;
its flags say so.

