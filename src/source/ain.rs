//! Reading Half-Life 2's compiled AI node graph, `maps/graphs/<map>.ain`, and
//! the pathfinding over it that the 2013 SDK's NPCs use.
//!
//! The file is what `CAI_NetworkManager::SaveNetworkGraph`
//! (`game/server/ai_networkmanager.cpp:209`) writes and `LoadNetworkGraph`
//! (`ai_networkmanager.cpp:475`) reads, a flat little-endian record stream
//! (`CUtlBuffer` with default flags, no byte swapping on PC):
//!
//! ```text
//! i32 version            (:262 `buf.PutInt(AINET_VERSION_NUMBER)`, 37 at :33)
//! i32 mapversion         (:263; the load check at :565 is waived for the
//!                         `hl2` and `episodic` game directories, :571-575)
//! i32 numNodes           (:268; load rejects > MAX_NODES = 1500, :596 and
//!                         `ai_network.h:36`)
//! numNodes x node, 61 bytes:
//!     f32 x, y, z        (:277-279)
//!     f32 yaw            (:280)
//!     f32 vOffset[10]    (:281 `buf.Put( pNode->m_flVOffset, ... )`)
//!     i8  nodeType       (:282 `buf.PutChar( pNode->GetType() )`; the 3 pad
//!                         bytes at :283-286 are Xbox 360 only)
//!     u16 nodeInfo       (:287 `buf.PutUnsignedShort( pNode->m_eNodeInfo )`)
//!     i16 zone           (:288 `buf.PutShort( pNode->GetZone() )`)
//! i32 totalNumLinks      (:303, read :648)
//! totalNumLinks x link, 14 bytes:
//!     i16 srcId, i16 destId   (:315-316)
//!     u8  acceptedMoveTypes[10]  (:317 `buf.Put( pLink->m_iAcceptedMoveTypes, ... )`)
//! numNodes x i32 Hammer node id  (:344 `buf.PutInt( ...m_pNodeIndexTable[node] )`)
//! ```
//!
//! Only the source side of each link is written (`if (node == pLink->m_iSrcID)`,
//! :316), so every link appears once; `CAI_Network::CreateLink`
//! (`ai_network.cpp:556-597`) adds the one object to both end nodes
//! (`:593-594`), so a link is two-way and its per-hull move types are shared
//! by both directions. `CreateLink` refuses a link to a missing node, a node to
//! itself and a node past `AI_MAX_NODE_LINKS` = 30 links (`:565-582`); the
//! loader still consumes the 10 move-type bytes (`ai_networkmanager.cpp:659-661`),
//! and so does `parse`, dropping the link and counting it in `skipped_links`.
//!
//! `CAI_Link::m_LinkInfo` (`ai_link.h:47`, flags at `ai_link.h:20-24`) is
//! runtime state and not in the file; `Link::info` is always 0 here.
//!
//! The pathfinder follows `CAI_Pathfinder::FindBestPath`
//! (`ai_pathfinder.cpp:279-370`) and `IsLinkUsable` (`:595-695`), minus the
//! parts that need a live NPC or entity (hint groups, jump-override hints,
//! stale links, dynamic links, `IsJumpLegal`).
//!
//! Everything is read defensively: counts and sizes come from a file, so a
//! truncated or corrupt one yields `None`, never a panic.

use crate::source::vfs::Vfs;

/// `NUM_HULLS` (`ai_hull.h:26`).
pub const NUM_HULLS: usize = 10;

/// `Hull_t` (`ai_hull.h:14-29`) as indices into every per-hull array.
pub const HULL_HUMAN: usize = 0;
pub const HULL_SMALL_CENTERED: usize = 1;
pub const HULL_WIDE_HUMAN: usize = 2;
pub const HULL_TINY: usize = 3;
pub const HULL_WIDE_SHORT: usize = 4;
pub const HULL_MEDIUM: usize = 5;
pub const HULL_TINY_CENTERED: usize = 6;
pub const HULL_LARGE: usize = 7;
pub const HULL_LARGE_CENTERED: usize = 8;
pub const HULL_MEDIUM_TALL: usize = 9;

/// Hull bounding boxes `(mins, maxs)` in Source units, the HL2 branch (the
/// `#else` of `#ifdef HL1_DLL`) of `ai_hull.cpp:40-49`.
pub const HULL_BOUNDS: [([f32; 3], [f32; 3]); NUM_HULLS] = [
    ([-13.0, -13.0, 0.0], [13.0, 13.0, 72.0]),
    ([-20.0, -20.0, -20.0], [20.0, 20.0, 20.0]),
    ([-15.0, -15.0, 0.0], [15.0, 15.0, 72.0]),
    ([-12.0, -12.0, 0.0], [12.0, 12.0, 24.0]),
    ([-35.0, -35.0, 0.0], [35.0, 35.0, 32.0]),
    ([-16.0, -16.0, 0.0], [16.0, 16.0, 64.0]),
    ([-8.0, -8.0, -4.0], [8.0, 8.0, 4.0]),
    ([-40.0, -40.0, 0.0], [40.0, 40.0, 100.0]),
    ([-38.0, -38.0, -38.0], [38.0, 38.0, 38.0]),
    ([-18.0, -18.0, 0.0], [18.0, 18.0, 100.0]),
];

/// `Capability_t` movement bits (`basecombatcharacter.h:46-51`), the values of
/// `Link::move_types` bytes and of an NPC's capabilities.
pub const CAP_MOVE_GROUND: u32 = 0x01;
pub const CAP_MOVE_JUMP: u32 = 0x02;
pub const CAP_MOVE_FLY: u32 = 0x04;
pub const CAP_MOVE_CLIMB: u32 = 0x08;
pub const CAP_MOVE_SWIM: u32 = 0x10;
pub const CAP_MOVE_CRAWL: u32 = 0x20;

/// `NodeType_e` (`ai_node.h:54-62`).
pub const NODE_ANY: i8 = 0;
pub const NODE_DELETED: i8 = 1;
pub const NODE_GROUND: i8 = 2;
pub const NODE_AIR: i8 = 3;
pub const NODE_CLIMB: i8 = 4;
pub const NODE_WATER: i8 = 5;

/// `NodeInfoBits_e` (`ai_node.h:64-97`) as stored in `Node::info`.
pub const NODE_CLIMB_BOTTOM: u16 = 1 << 0;
pub const NODE_CLIMB_ON: u16 = 1 << 1;
pub const NODE_CLIMB_OFF_FORWARD: u16 = 1 << 2;
pub const NODE_CLIMB_OFF_LEFT: u16 = 1 << 3;
pub const NODE_CLIMB_OFF_RIGHT: u16 = 1 << 4;
/// Hammer spawnflags live at bit 5 and up (`NODE_ENT_FLAGS_SHIFT`,
/// `ai_node.h:76`); the "node fits this hull" flag of hull `h` is bit `5 + h`
/// (comment at `ai_node.h:78-84`).
pub const NODE_ENT_FLAGS_SHIFT: u16 = 5;
/// `bits_DONT_DROP` (`ai_node.h:86`). It is bit 14, which is also hull 9's
/// (`HULL_MEDIUM_TALL`) fit flag, `0x200 << 5`: the two cannot be told apart.
pub const NODE_DONT_DROP: u16 = 1 << 14;

/// `Link_Info_t` (`ai_link.h:20-24`), runtime only.
pub const LINK_STALE_SUGGESTED: u8 = 0x01;
pub const LINK_OFF: u8 = 0x02;

/// `AI_ZoneIds_t` (`ai_node.h:22-28`).
pub const ZONE_UNKNOWN: i16 = 0;
pub const ZONE_SOLO: i16 = 1;
pub const ZONE_UNIVERSAL: i16 = 3;

/// `NO_NODE` (`ai_node.h:36`): a node with no Hammer id.
pub const NO_NODE: i32 = -1;

/// `AINET_VERSION_NUMBER` (`ai_networkmanager.cpp:33`).
pub const AINET_VERSION: i32 = 37;
/// `MAX_NODES` (`ai_network.h:36`).
pub const MAX_NODES: usize = 1500;
/// `AI_MAX_NODE_LINKS` (`ai_network.h:35`).
pub const MAX_NODE_LINKS: usize = 30;

const NODE_SIZE: usize = 61;
const LINK_SIZE: usize = 4 + NUM_HULLS;

/// One `CAI_Node`, saved fields only (`ai_node.h:104-162`).
#[derive(Clone, Debug, PartialEq)]
pub struct Node {
    /// `m_vOrigin` in Source units (the Hammer entity's origin).
    pub origin: [f32; 3],
    /// `m_flYaw` in degrees.
    pub yaw: f32,
    /// `m_flVOffset[hull]`: for a ground node, where the hull rests relative
    /// to `origin.z` (`ai_networkmanager.cpp:2552-2554`).
    pub v_offset: [f32; NUM_HULLS],
    /// `m_eNodeType`, a `NODE_*` value.
    pub node_type: i8,
    /// `m_eNodeInfo`, truncated to 16 bits by the writer (`:287`).
    pub info: u16,
    /// `m_zone`: nodes connected by links share a zone; `ZONE_SOLO` has none.
    pub zone: i16,
    /// Hammer node id from the lookup table, `NO_NODE` when it has none.
    pub wc_id: i32,
}

impl Node {
    /// `CAI_Node::GetPosition(hull)` (`ai_node.cpp:201-245`): the hull's
    /// resting position for a ground node (`:239`), the ladder position
    /// shifted off the climb surface for a climb node (`:204-234`), the origin
    /// for the rest (`:243`).
    pub fn position(&self, hull: usize) -> [f32; 3] {
        let [x, y, z] = self.origin;
        let voff = self.v_offset.get(hull).copied().unwrap_or(0.0);
        match self.node_type {
            NODE_CLIMB => {
                // NAI_Hull::Length is maxs.x - mins.x (`ai_hull.cpp:116-119`),
                // NODE_CLIMB_OFFSET is 8 (`ai_node.h:45`).
                let length = HULL_BOUNDS.get(hull).map_or(0.0, |(mn, mx)| mx[0] - mn[0]);
                let shift = 0.5 * length + 8.0;
                let rad = self.yaw.to_radians();
                let dir = [rad.cos(), rad.sin()];
                // `CrossProduct(offsetDir, (0,0,1))` = (dir.y, -dir.x, 0).
                let left = [dir[1], -dir[0]];
                let (px, py) = if self.info & NODE_CLIMB_OFF_FORWARD != 0 {
                    (x + shift * dir[0], y + shift * dir[1])
                } else if self.info & NODE_CLIMB_OFF_LEFT != 0 {
                    (
                        x - 2.0 * shift * left[0] - shift * dir[0],
                        y - 2.0 * shift * left[1] - shift * dir[1],
                    )
                } else if self.info & NODE_CLIMB_OFF_RIGHT != 0 {
                    (
                        x + 2.0 * shift * left[0] - shift * dir[0],
                        y + 2.0 * shift * left[1] - shift * dir[1],
                    )
                } else {
                    (x - shift * dir[0], y - shift * dir[1])
                };
                [px, py, z + voff]
            }
            NODE_GROUND => [x, y, z + voff],
            _ => self.origin,
        }
    }
}

/// One `CAI_Link`: a two-way edge between two nodes.
#[derive(Clone, Debug, PartialEq)]
pub struct Link {
    /// `m_iSrcID`, the node that owns the link.
    pub src: usize,
    /// `m_iDestID`.
    pub dest: usize,
    /// `m_iAcceptedMoveTypes[hull]`: the `CAP_MOVE_*` bits a hull may use
    /// this link with. Shared by both directions. 0 means the hull cannot use
    /// it.
    pub move_types: [u8; NUM_HULLS],
    /// `m_LinkInfo`, a `LINK_*` mask. Not stored in the file, so 0.
    pub info: u8,
}

impl Link {
    /// `CAI_Link::DestNodeID(srcID)` (`ai_link.cpp:27-37`).
    pub fn other(&self, node: usize) -> usize {
        if node == self.src {
            self.dest
        } else {
            self.src
        }
    }
}

/// A parsed node graph.
#[derive(Clone, Debug, Default)]
pub struct NodeGraph {
    /// File version, `AINET_VERSION` (37) for every graph this accepts.
    pub version: i32,
    /// `gpGlobals->mapversion` the graph was built for. Not compared to
    /// anything: the SDK waives the check for `hl2` (`ai_networkmanager.cpp:571-575`).
    pub map_version: i32,
    pub nodes: Vec<Node>,
    pub links: Vec<Link>,
    /// Links the file lists that `CreateLink` would have refused (missing
    /// node, self link, node past 30 links) and that were dropped.
    pub skipped_links: usize,
    /// Per node, the indices into `links` of every link touching it, in file
    /// order (`CAI_Node::m_Links`).
    adjacency: Vec<Vec<usize>>,
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.at.checked_add(n)?;
        let s = self.bytes.get(self.at..end)?;
        self.at = end;
        Some(s)
    }
    fn i32(&mut self) -> Option<i32> {
        Some(i32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
    fn f32(&mut self) -> Option<f32> {
        Some(f32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
    fn i16(&mut self) -> Option<i16> {
        Some(i16::from_le_bytes(self.take(2)?.try_into().ok()?))
    }
    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_le_bytes(self.take(2)?.try_into().ok()?))
    }
    fn i8(&mut self) -> Option<i8> {
        Some(self.take(1)?[0] as i8)
    }
}

/// Parses an `.ain` file. `None` when it is the old text format (starts with
/// `Ver`, `ai_networkmanager.cpp:542`), has another version than 37 (`:556`),
/// is truncated, or declares impossible counts. Bytes after the Hammer id
/// table are ignored.
pub fn parse(bytes: &[u8]) -> Option<NodeGraph> {
    if bytes.starts_with(b"Ver") {
        return None;
    }
    let mut r = Reader { bytes, at: 0 };
    let version = r.i32()?;
    if version != AINET_VERSION {
        return None;
    }
    let map_version = r.i32()?;
    let node_count = r.i32()?;
    if node_count < 0 || node_count as usize > MAX_NODES {
        return None;
    }
    let node_count = node_count as usize;
    // The whole fixed-size node block must be present before any allocation.
    if bytes.len().saturating_sub(r.at) < node_count * NODE_SIZE {
        return None;
    }

    let mut nodes = Vec::with_capacity(node_count);
    for _ in 0..node_count {
        let origin = [r.f32()?, r.f32()?, r.f32()?];
        let yaw = r.f32()?;
        let mut v_offset = [0.0; NUM_HULLS];
        for v in &mut v_offset {
            *v = r.f32()?;
        }
        nodes.push(Node {
            origin,
            yaw,
            v_offset,
            node_type: r.i8()?,
            info: r.u16()?,
            zone: r.i16()?,
            wc_id: NO_NODE,
        });
    }

    let link_count = r.i32()?;
    if link_count < 0 || (link_count as usize).checked_mul(LINK_SIZE)? > bytes.len() - r.at {
        return None;
    }
    let mut links = Vec::with_capacity(link_count as usize);
    let mut adjacency = vec![Vec::new(); node_count];
    let mut skipped_links = 0;
    for _ in 0..link_count {
        let src = r.i16()?;
        let dest = r.i16()?;
        let mut move_types = [0u8; NUM_HULLS];
        move_types.copy_from_slice(r.take(NUM_HULLS)?);
        let (Ok(src), Ok(dest)) = (usize::try_from(src), usize::try_from(dest)) else {
            skipped_links += 1;
            continue;
        };
        if src >= node_count
            || dest >= node_count
            || src == dest
            || adjacency[src].len() >= MAX_NODE_LINKS
            || adjacency[dest].len() >= MAX_NODE_LINKS
        {
            skipped_links += 1;
            continue;
        }
        adjacency[src].push(links.len());
        adjacency[dest].push(links.len());
        links.push(Link {
            src,
            dest,
            move_types,
            info: 0,
        });
    }

    for node in &mut nodes {
        node.wc_id = r.i32()?;
    }

    Some(NodeGraph {
        version,
        map_version,
        nodes,
        links,
        skipped_links,
        adjacency,
    })
}

/// Reads `maps/graphs/<map_name>.ain` (`ai_networkmanager.cpp:488-500`,
/// read with `filesystem->ReadFile(..., "game", ...)` at `:531`) from the
/// search path, loose or from an archive.
pub fn read(vfs: &Vfs, map_name: &str) -> Option<NodeGraph> {
    let name = map_name.trim_end_matches(".bsp");
    parse(&vfs.open(&format!("maps/graphs/{name}.ain"))?)
}

/// Whether a link may be walked by an NPC with capabilities `move_type_mask`
/// and hull `hull`: not switched off (`IsLinkUsable`, `ai_pathfinder.cpp:600-620`,
/// minus dynamic links) and with a move type in common
/// (`linkMoveTypes & CapabilitiesGet()` non-zero, `:630-631`, `:657-660`).
/// The JUMP-override hint exception (`:638-655`), `IsUnusableNode`, `IsJumpLegal`
/// and stale-link checks need a live NPC and are not applied.
pub fn link_usable(link: &Link, hull: usize, move_type_mask: u32) -> bool {
    link.info & LINK_OFF == 0
        && link
            .move_types
            .get(hull)
            .is_some_and(|&m| u32::from(m) & move_type_mask != 0)
}

impl NodeGraph {
    /// Every link touching `node` with the node at its other end, in file
    /// order. Empty for an index out of range.
    pub fn neighbours(&self, node: usize) -> impl Iterator<Item = (usize, usize)> + '_ {
        self.adjacency
            .get(node)
            .into_iter()
            .flatten()
            .map(move |&l| (l, self.links[l].other(node)))
    }

    /// `CAI_Network::IsConnected` (`ai_network.cpp:603-632`): the zone test
    /// `BuildNodeRoute` makes before searching (`ai_pathfinder.cpp:1806-1807`).
    pub fn is_connected(&self, a: usize, b: usize) -> bool {
        let (Some(na), Some(nb)) = (self.nodes.get(a), self.nodes.get(b)) else {
            return false;
        };
        if a == b {
            return true;
        }
        if na.zone == ZONE_SOLO || nb.zone == ZONE_SOLO {
            return false;
        }
        if na.zone == ZONE_UNIVERSAL || nb.zone == ZONE_UNIVERSAL {
            return true;
        }
        na.zone == nb.zone
    }

    /// The ground node closest to `point` (Source units, straight-line
    /// distance to its `origin`) that has at least one link the hull can use.
    ///
    /// This is deliberately simpler than `CAI_Network::NearestNodeToPoint`
    /// (`ai_network.cpp:261-376`): the SDK's candidate box of 720 units, the
    /// 10-node trace budget, the visibility trace from the point
    /// (`:340-355`), `CanFitAtNode` (`:337-338`), the position cache and the
    /// `CPathfindNearestNodeFilter` route test (`ai_pathfinder.cpp:1695-1728`)
    /// are not reproduced. Only `CNodeFilter::NodeIsValid`'s "ground node for a
    /// walker" rule (`ai_network.cpp:72-74`) and the link filter's "has a link
    /// with a move type for the hull" (`ai_pathfinder.cpp:1697-1716`) are.
    pub fn nearest_node(&self, point: [f32; 3], hull: usize) -> Option<usize> {
        let mut best: Option<(f32, usize)> = None;
        for (i, node) in self.nodes.iter().enumerate() {
            if node.node_type != NODE_GROUND {
                continue;
            }
            let usable = self
                .neighbours(i)
                .any(|(l, _)| link_usable(&self.links[l], hull, CAP_MOVE_GROUND | CAP_MOVE_JUMP | CAP_MOVE_CLIMB));
            if !usable {
                continue;
            }
            let d = dist_sq(node.origin, point);
            if best.map_or(true, |(bd, _)| d < bd) {
                best = Some((d, i));
            }
        }
        best.map(|(_, i)| i)
    }

    /// The A* of `CAI_Pathfinder::FindBestPath` (`ai_pathfinder.cpp:279-370`)
    /// for an NPC of hull `hull` with movement capabilities `moves`
    /// (`CAP_MOVE_*` bits). Returns the node indices from `start` to `goal`
    /// inclusive.
    ///
    /// Like the SDK it:
    /// - costs a link by the straight-line distance between the two nodes'
    ///   `GetPosition(hull)` (`:347-349`), doubled when the link's usable
    ///   move type is exactly JUMP or exactly CLIMB
    ///   (`CAI_Navigator::MovementCost`, `ai_navigator.cpp:3213-3228`);
    /// - uses the full straight-line distance to the goal as the heuristic for
    ///   every node but the start (`:360`; the start's is scaled by 0.1,
    ///   `:310`), which can overestimate, so the path is not always the
    ///   cheapest;
    /// - pops the open node with the lowest F, the lowest index on a tie
    ///   (`CAI_Network::FindBSSmallest`, `ai_network.cpp:178-191`), and
    ///   re-opens a node whenever a cheaper way to it is found (`:356-365`).
    ///
    /// Unlike the SDK, a `NODE_DELETED` node is never entered (the SDK has no
    /// such test in the search itself).
    pub fn find_path(&self, start: usize, goal: usize, hull: usize, moves: u32) -> Option<Vec<usize>> {
        let n = self.nodes.len();
        if start >= n || goal >= n || hull >= NUM_HULLS {
            return None;
        }
        if start == goal {
            return Some(vec![start]);
        }
        let pos: Vec<[f32; 3]> = self.nodes.iter().map(|nd| nd.position(hull)).collect();
        let mut g = vec![f32::MAX; n];
        let mut f = vec![f32::MAX; n];
        let mut parent = vec![usize::MAX; n];
        let mut seen = vec![false; n];
        let mut open = vec![false; n];
        g[start] = 0.0;
        f[start] = 0.1 * dist(pos[start], pos[goal]);
        seen[start] = true;
        open[start] = true;

        loop {
            let mut cur = usize::MAX;
            let mut best = f32::MAX;
            for i in 0..n {
                if open[i] && f[i] < best {
                    best = f[i];
                    cur = i;
                }
            }
            if cur == usize::MAX {
                return None;
            }
            open[cur] = false;
            if cur == goal {
                let mut path = vec![goal];
                let mut at = goal;
                while parent[at] != usize::MAX {
                    at = parent[at];
                    path.push(at);
                    if path.len() > n {
                        return None;
                    }
                }
                path.reverse();
                return Some(path);
            }
            for (l, next) in self.neighbours(cur) {
                let link = &self.links[l];
                if !link_usable(link, hull, moves) || self.nodes[next].node_type == NODE_DELETED {
                    continue;
                }
                let move_type = u32::from(link.move_types[hull]) & moves;
                let mut cost = dist(pos[cur], pos[next]);
                if move_type == CAP_MOVE_JUMP || move_type == CAP_MOVE_CLIMB {
                    cost *= 2.0;
                }
                let new_g = g[cur] + cost;
                if !seen[next] || new_g < g[next] {
                    parent[next] = cur;
                    g[next] = new_g;
                    f[next] = new_g + dist(pos[next], pos[goal]);
                    seen[next] = true;
                    open[next] = true;
                }
            }
        }
    }

    /// The straight-line length of `path` as `find_path` costs it for plain
    /// walking, for tests and tooling.
    pub fn path_length(&self, path: &[usize], hull: usize) -> f32 {
        path.windows(2)
            .map(|w| dist(self.nodes[w[0]].position(hull), self.nodes[w[1]].position(hull)))
            .sum()
    }
}

fn dist_sq(a: [f32; 3], b: [f32; 3]) -> f32 {
    (0..3).map(|i| (a[i] - b[i]) * (a[i] - b[i])).sum()
}

fn dist(a: [f32; 3], b: [f32; 3]) -> f32 {
    dist_sq(a, b).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    const HL2: &str = "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2";
    const MAPS: [&str; 6] = [
        "d1_trainstation_01",
        "d1_trainstation_02",
        "d1_trainstation_03",
        "d1_trainstation_04",
        "d1_trainstation_05",
        "d1_trainstation_06",
    ];

    /// Builds a file the way `SaveNetworkGraph` lays it out.
    fn build(nodes: &[([f32; 3], i8, i16)], links: &[(i16, i16, [u8; NUM_HULLS])]) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend(AINET_VERSION.to_le_bytes());
        b.extend(4048i32.to_le_bytes());
        b.extend((nodes.len() as i32).to_le_bytes());
        for (o, t, zone) in nodes {
            for c in o {
                b.extend(c.to_le_bytes());
            }
            b.extend(90f32.to_le_bytes());
            for _ in 0..NUM_HULLS {
                b.extend(0f32.to_le_bytes());
            }
            b.push(*t as u8);
            b.extend(0u16.to_le_bytes());
            b.extend(zone.to_le_bytes());
        }
        b.extend((links.len() as i32).to_le_bytes());
        for (s, d, m) in links {
            b.extend(s.to_le_bytes());
            b.extend(d.to_le_bytes());
            b.extend(m);
        }
        for i in 0..nodes.len() {
            b.extend((100 + i as i32).to_le_bytes());
        }
        b
    }

    fn walk() -> [u8; NUM_HULLS] {
        [1; NUM_HULLS]
    }

    #[test]
    fn layout_and_helpers_on_a_synthetic_graph() {
        // 0 - 1 - 2 in a line, 0 - 3 - 2 a longer way round, 4 isolated.
        let nodes = [
            ([0.0, 0.0, 0.0], NODE_GROUND, 4),
            ([100.0, 0.0, 0.0], NODE_GROUND, 4),
            ([200.0, 0.0, 0.0], NODE_GROUND, 4),
            ([100.0, 300.0, 0.0], NODE_GROUND, 4),
            ([500.0, 500.0, 0.0], NODE_GROUND, 1),
        ];
        let links = [
            (0, 1, walk()),
            (1, 2, walk()),
            (0, 3, walk()),
            (3, 2, walk()),
            (0, 0, walk()),  // self link, refused by CreateLink
            (0, 99, walk()), // missing node
        ];
        let bytes = build(&nodes, &links);
        assert_eq!(bytes.len(), 12 + 61 * 5 + 4 + 14 * 6 + 4 * 5);
        let g = parse(&bytes).expect("parses");
        assert_eq!((g.nodes.len(), g.links.len(), g.skipped_links), (5, 4, 2));
        assert_eq!(g.nodes[2].wc_id, 102);
        assert_eq!(g.nodes[1].yaw, 90.0);
        assert_eq!(g.neighbours(0).collect::<Vec<_>>(), vec![(0, 1), (2, 3)]);
        assert_eq!(g.neighbours(1).collect::<Vec<_>>(), vec![(0, 0), (1, 2)]);
        assert_eq!(g.neighbours(99).count(), 0);
        assert!(g.is_connected(0, 2) && !g.is_connected(0, 4));
        assert_eq!(g.nearest_node([210.0, 5.0, 0.0], HULL_HUMAN), Some(2));
        // Node 4 is closest to this point but has no links.
        assert_eq!(g.nearest_node([500.0, 500.0, 0.0], HULL_HUMAN), Some(3));
        assert_eq!(g.find_path(0, 2, HULL_HUMAN, CAP_MOVE_GROUND), Some(vec![0, 1, 2]));
        assert_eq!(g.find_path(2, 2, HULL_HUMAN, CAP_MOVE_GROUND), Some(vec![2]));
        assert_eq!(g.find_path(0, 4, HULL_HUMAN, CAP_MOVE_GROUND), None);
        // A walker cannot use a link that only allows flying.
        assert_eq!(g.find_path(0, 2, HULL_HUMAN, CAP_MOVE_FLY), None);
    }

    #[test]
    fn rejects_garbage() {
        let good = build(&[([0.0; 3], NODE_GROUND, 4)], &[]);
        assert!(parse(&good).is_some());
        assert!(parse(&[]).is_none());
        assert!(parse(b"Version 37 text format").is_none());
        for cut in 0..good.len() {
            assert!(parse(&good[..cut]).is_none(), "cut at {cut}");
        }
        let mut bad = good.clone();
        bad[0] = 36;
        assert!(parse(&bad).is_none());
        let mut huge = good.clone();
        huge[8..12].copy_from_slice(&i32::MAX.to_le_bytes());
        assert!(parse(&huge).is_none());
        let mut neg = good.clone();
        neg[8..12].copy_from_slice(&(-1i32).to_le_bytes());
        assert!(parse(&neg).is_none());
        let mut many_links = good.clone();
        let at = 12 + NODE_SIZE;
        many_links[at..at + 4].copy_from_slice(&i32::MAX.to_le_bytes());
        assert!(parse(&many_links).is_none());
    }

    #[test]
    fn jump_and_climb_cost_double() {
        // 0 -(jump)- 1 -(jump)- 2 is 400 units of jumping, costed 800; the
        // walk 0 - 3 - 2 is 500 units and wins.
        let nodes = [
            ([0.0, 0.0, 0.0], NODE_GROUND, 4),
            ([200.0, 0.0, 0.0], NODE_GROUND, 4),
            ([400.0, 0.0, 0.0], NODE_GROUND, 4),
            ([200.0, 150.0, 0.0], NODE_GROUND, 4),
        ];
        let jump = [CAP_MOVE_JUMP as u8; NUM_HULLS];
        let links = [(0, 1, jump), (1, 2, jump), (0, 3, walk()), (3, 2, walk())];
        let g = parse(&build(&nodes, &links)).unwrap();
        let caps = CAP_MOVE_GROUND | CAP_MOVE_JUMP;
        assert_eq!(g.find_path(0, 2, HULL_HUMAN, caps), Some(vec![0, 3, 2]));
        assert_eq!(g.find_path(0, 2, HULL_HUMAN, CAP_MOVE_JUMP), Some(vec![0, 1, 2]));
    }

    #[test]
    fn real_trainstation_graphs() {
        let bsp = Path::new(HL2).join("maps/d1_trainstation_01.bsp");
        if !bsp.exists() {
            return;
        }
        let vfs = Vfs::for_map(&bsp, &[] as &[std::path::PathBuf]);
        let mut ground_total = 0;
        let mut paths_found = 0;
        for name in MAPS {
            let Some(g) = read(&vfs, name) else {
                panic!("{name}: no graph");
            };
            let count = |t| g.nodes.iter().filter(|n| n.node_type == t).count();
            let ground = count(NODE_GROUND);
            ground_total += ground;
            eprintln!(
                "{name}: version {} mapversion {} nodes {} (ground {ground}, air {}, climb {}, deleted {}) links {} skipped {}",
                g.version,
                g.map_version,
                g.nodes.len(),
                count(NODE_AIR),
                count(NODE_CLIMB),
                count(NODE_DELETED),
                g.links.len(),
                g.skipped_links,
            );
            assert_eq!(g.version, AINET_VERSION);
            assert!(ground > 0, "{name}: no ground nodes");
            assert!(g.links.len() > 0, "{name}: no links");
            assert_eq!(g.skipped_links, 0, "{name}");
            for l in &g.links {
                assert!(l.src < g.nodes.len() && l.dest < g.nodes.len() && l.src != l.dest);
                assert!(l.move_types.iter().any(|&m| m != 0), "{name}: link usable by no hull");
            }
            // Linked nodes share a zone.
            for l in &g.links {
                assert_eq!(g.nodes[l.src].zone, g.nodes[l.dest].zone, "{name}");
            }

            // A path between two far-apart human-usable ground nodes of the
            // same zone, every step a usable link.
            let caps = CAP_MOVE_GROUND;
            let walkable: Vec<usize> = (0..g.nodes.len())
                .filter(|&i| {
                    g.nodes[i].node_type == NODE_GROUND
                        && g.neighbours(i).any(|(l, _)| link_usable(&g.links[l], HULL_HUMAN, caps))
                })
                .collect();
            if walkable.len() < 2 {
                continue;
            }
            let a = walkable[0];
            let b = *walkable
                .iter()
                .filter(|&&i| g.is_connected(a, i))
                .max_by(|&&x, &&y| {
                    dist_sq(g.nodes[a].origin, g.nodes[x].origin)
                        .total_cmp(&dist_sq(g.nodes[a].origin, g.nodes[y].origin))
                })
                .unwrap();
            let path = match g.find_path(a, b, HULL_HUMAN, caps) {
                Some(p) => p,
                None => {
                    // The zone also joins nodes over jump links.
                    let jumping = g.find_path(a, b, HULL_HUMAN, caps | CAP_MOVE_JUMP);
                    eprintln!("{name}: no walking path {a} -> {b}; with jumps: {}", jumping.is_some());
                    continue;
                }
            };
            paths_found += 1;
            assert_eq!((path[0], *path.last().unwrap()), (a, b));
            for w in path.windows(2) {
                let ok = g
                    .neighbours(w[0])
                    .any(|(l, other)| other == w[1] && link_usable(&g.links[l], HULL_HUMAN, caps));
                assert!(ok, "{name}: step {} -> {} is not a usable link", w[0], w[1]);
            }
            eprintln!("{name}: path {a} -> {b}: {} nodes, {:.0} units", path.len(), g.path_length(&path, HULL_HUMAN));
        }
        eprintln!("ground nodes over trainstation 01-06: {ground_total}");
        assert!(ground_total > 500);
        // The zone is built from every hull's links, so a human cannot always
        // walk between two nodes of one zone; most maps still have such a pair.
        assert!(paths_found >= 4, "only {paths_found} maps gave a walking path");
    }
}
