//! Displacement surfaces: Source's terrain.
//!
//! A displacement replaces one brush face with a subdivided grid of vertices,
//! each pushed along a per-vertex direction. The result is a heightfield, not a
//! solid: the brush behind it stops being solid at compile time and the
//! displacement surface itself becomes the collision. So unlike a brush there
//! is no inside to test against, only triangles.
//!
//! `vbsp` already reconstructs the displaced vertex grid and triangulates it,
//! which is the fiddly part — the base face's corners have to be rotated so
//! they start at the displacement's recorded start position, or the terrain
//! comes out mirrored.

use crate::geom::{Aabb, Vec3};
use crate::voxel::mesh::Triangle;

/// One displacement, as triangles in Source space.
#[derive(Debug, Clone)]
pub struct Surface {
    pub index: usize,
    pub triangles: Vec<Triangle>,
    /// The side the displacement is seen from, which is the side the player
    /// walks on; solid material lies behind it. It is the outward normal of the
    /// face it was built on, turned round for an inverted displacement, whose
    /// triangles wind the other way.
    pub normal: Vec3,
    /// Index into [`crate::bsp::Map::materials`].
    pub material: Option<usize>,
    /// The face's texture projection, for choosing which piece of a split
    /// texture belongs at each voxel of the terrain.
    pub texcoord: Option<super::texcoord::TexCoord>,
    pub bounds: Aabb,
}

impl super::Map {
    /// Every displacement in the map, triangulated.
    pub fn displacement_surfaces(&self) -> Vec<Surface> {
        (0..self.bsp.displacements.len())
            .filter_map(|index| self.displacement_surface(index))
            .collect()
    }

    fn displacement_surface(&self, index: usize) -> Option<Surface> {
        let disp = self.bsp.displacement(index)?;

        let mut triangles = Vec::new();
        let mut bounds = Aabb::empty();
        let mut corners = Vec::with_capacity(3);
        for vertex in disp.triangulated_displaced_vertices() {
            corners.push(Vec3::from(vertex));
            if corners.len() == 3 {
                let tri = Triangle::new(corners[0], corners[1], corners[2]);
                if !tri.is_degenerate() {
                    bounds.extend(tri.a);
                    bounds.extend(tri.b);
                    bounds.extend(tri.c);
                    triangles.push(tri);
                }
                corners.clear();
            }
        }
        if triangles.is_empty() {
            return None;
        }

        let face = disp.face()?;
        let plane = self.bsp.planes.get(face.plane_num as usize)?;
        // A face records which side of its plane it faces on; side 1 means the
        // stored normal points away from the surface.
        let base = if face.side == 0 {
            Vec3::from(plane.normal)
        } else {
            -Vec3::from(plane.normal)
        };
        // Hammer can invert a displacement, mirroring it through its base
        // plane. A mirror reverses the winding, and Source culls by winding, so
        // an inverted displacement is seen from the side opposite its base face:
        // INFRA builds cave ceilings on upward faces this way (56 of 135 in
        // infra_c6_m4_waterplant). Terrain can fold, so it is the winding of the
        // whole surface that decides, not any single triangle.
        let winding: f64 = triangles
            .iter()
            .map(|t| (t.b - t.a).cross(t.c - t.a).dot(base))
            .sum();
        let normal = if winding < 0.0 { -base } else { base };

        Some(Surface {
            index,
            triangles,
            normal: normal.normalized(),
            material: self.material_index(face.texture_info as usize),
            texcoord: self
                .bsp
                .textures_info
                .get(face.texture_info as usize)
                .map(super::texcoord::TexCoord::of),
            bounds,
        })
    }
}

#[cfg(test)]
mod tests {

    use crate::bsp::Map;
    use std::path::Path;

    /// A map with real terrain in it; `az_c4_4` is mostly interiors.
    fn terrain_map() -> Option<Map> {
        for path in [
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_town_01.bsp",
            "/mnt/games/SteamLibrary/steamapps/common/Entropy Zero/Entropy Zero/EntropyZero/maps/az_c4_4.bsp",
        ] {
            let path = Path::new(path);
            if path.exists() {
                let map = Map::load(path).unwrap();
                if !map.bsp.displacements.is_empty() {
                    return Some(map);
                }
            }
        }
        None
    }

    #[test]
    fn every_displacement_yields_triangles() {
        let Some(map) = terrain_map() else { return };
        let surfaces = map.displacement_surfaces();
        assert_eq!(
            surfaces.len(),
            map.bsp.displacements.len(),
            "some displacements produced no geometry"
        );

        for surface in &surfaces {
            // A displacement of power p is a 2^p x 2^p grid of quads, so two
            // triangles each. Powers run 2..4, giving 32, 128 or 512.
            assert!(
                [32, 128, 512].contains(&surface.triangles.len()),
                "displacement {} has {} triangles",
                surface.index,
                surface.triangles.len()
            );
            assert!(!surface.bounds.is_empty());
            assert!(surface.bounds.min.is_finite() && surface.bounds.max.is_finite());
        }
    }

    #[test]
    fn surfaces_carry_a_unit_normal_and_a_material() {
        let Some(map) = terrain_map() else { return };
        let surfaces = map.displacement_surfaces();

        for surface in surfaces.iter().take(200) {
            assert!(
                (surface.normal.length() - 1.0).abs() < 1e-6,
                "displacement {} normal is {:?}",
                surface.index,
                surface.normal
            );
            assert!(
                surface.material.is_some(),
                "displacement {} has no material",
                surface.index
            );
        }

        // Terrain is mostly ground, so most displacements should face up.
        let up = surfaces.iter().filter(|s| s.normal.z > 0.5).count();
        assert!(
            up * 2 > surfaces.len(),
            "only {up} of {} face up",
            surfaces.len()
        );
    }

    /// INFRA's waterplant builds cave ceilings from inverted displacements on
    /// upward faces; each must face the way its triangles wind, down into the
    /// cave, not the way its base face points.
    #[test]
    fn inverted_displacements_face_the_way_they_wind() {
        let path = "/mnt/games/SteamLibrary/steamapps/common/infra/infra/pak02_dir.vpk:maps/infra_c6_m4_waterplant.bsp";
        if !Path::new(path.split(".vpk:").next().unwrap()).with_extension("vpk").exists() {
            return;
        }
        let map = Map::load(Path::new(path)).unwrap();
        let surfaces = map.displacement_surfaces();
        for s in &surfaces {
            let winding: f64 = s.triangles.iter().map(|t| (t.b - t.a).cross(t.c - t.a).dot(s.normal)).sum();
            assert!(winding > 0.0, "displacement {} winds against its normal", s.index);
        }
        let ceiling = surfaces.iter().find(|s| s.index == 12).expect("displacement 12");
        assert!(ceiling.normal.z < -0.99, "the cave ceiling faces down, got {:?}", ceiling.normal);
    }

    #[test]
    fn displacements_stay_inside_the_map() {
        let Some(map) = terrain_map() else { return };
        let world = map.bounds();
        for surface in map.displacement_surfaces().iter().take(200) {
            assert!(surface.bounds.min.x >= world.min.x - 64.0);
            assert!(surface.bounds.max.z <= world.max.z + 64.0);
        }
    }
}
