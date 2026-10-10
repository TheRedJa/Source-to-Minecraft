//! Cubemaps: the six faces of an `env_cubemap` or static environment map VTF.
//!
//! The `vtf` crate reads one face of a single-frame texture, and for a cubemap
//! it skips the wrong number of bytes for the smaller mips, which hold six
//! faces each; nor does it decode `RGBA16161616F`, which every HDR cubemap is.
//! This reads the largest mip of each face itself.

use anyhow::{Context, Result, bail, ensure};

/// `TEXTUREFLAGS_ENVMAP`.
const FLAG_ENVMAP: u32 = 0x4000;
/// The high-resolution image's resource tag (VTF 7.3+).
const HIGH_RES_TAG: [u8; 3] = [0x30, 0, 0];

/// A cubemap's largest mip: six square faces in VTF order, which is Direct3D's
/// (+X, -X, +Y, -Y, +Z, -Z in Source axes, each row by row from the top), as
/// the material system uploads them; linear RGB.
#[derive(Debug, Clone, PartialEq)]
pub struct Cubemap {
    pub size: u32,
    pub faces: [Vec<[f32; 3]>; 6],
}

/// Bytes of one `width` x `height` image in VTF format `format`.
fn image_bytes(format: i32, width: u32, height: u32) -> Result<usize> {
    let (w, h) = (width.max(1) as usize, height.max(1) as usize);
    let blocks = w.div_ceil(4) * h.div_ceil(4);
    Ok(match format {
        0 | 1 | 11 | 12 | 16 => w * h * 4,
        2 | 3 => w * h * 3,
        13 | 20 => blocks * 8,
        14 | 15 => blocks * 16,
        24 | 25 => w * h * 8,
        _ => bail!("unsupported cubemap format {format}"),
    })
}

/// Decodes a cubemap VTF's largest mip. 8-bit faces are gamma encoded, as
/// Source reads an LDR cubemap with sRGB decode; `RGBA16161616F` is linear.
pub fn decode(data: &[u8]) -> Result<Cubemap> {
    ensure!(data.len() >= 80 && &data[..4] == b"VTF\0", "not a VTF");
    let u32_at = |at: usize| u32::from_le_bytes(data[at..at + 4].try_into().unwrap());
    let u16_at = |at: usize| u16::from_le_bytes(data[at..at + 2].try_into().unwrap());
    let minor = u32_at(8);
    let header_size = u32_at(12) as usize;
    let (width, height) = (u32::from(u16_at(16)), u32::from(u16_at(18)));
    let flags = u32_at(20);
    let frames = u32::from(u16_at(24)).max(1);
    let first_frame = u16_at(26);
    let format = i32::from_le_bytes(data[52..56].try_into().unwrap());
    let mips = u32::from(data[56]).max(1);
    let low_format = i32::from_le_bytes(data[57..61].try_into().unwrap());
    let (low_width, low_height) = (u32::from(data[61]), u32::from(data[62]));
    let depth = if minor >= 2 { u32::from(u16_at(63)).max(1) } else { 1 };
    ensure!(flags & FLAG_ENVMAP != 0, "not a cubemap");
    ensure!(width == height && width > 0 && width <= 4096, "cubemap faces are not square");
    // VTFLib's face count: before 7.5 an environment map also carries a sphere map.
    let faces = if minor < 5 && first_frame != 0xFFFF { 7 } else { 6 };
    let start = if minor >= 3 {
        let count = u32_at(68) as usize;
        (0..count)
            .map(|i| 80 + i * 8)
            .filter(|&at| at + 8 <= data.len())
            .find(|&at| data[at..at + 3] == HIGH_RES_TAG)
            .map(|at| u32_at(at + 4) as usize)
            .context("cubemap has no image resource")?
    } else {
        let low = if low_format < 0 || low_width == 0 { 0 } else { image_bytes(low_format, low_width, low_height)? };
        header_size + low
    };
    // Mips run smallest first, each holding every frame, face and slice.
    let mut offset = start;
    for mip in (1..mips).rev() {
        offset += image_bytes(format, width >> mip, height >> mip)? * (frames * faces * depth) as usize;
    }
    let face_bytes = image_bytes(format, width, height)?;
    let mut out: [Vec<[f32; 3]>; 6] = Default::default();
    for (face, pixels) in out.iter_mut().enumerate() {
        let at = offset + face * face_bytes * depth as usize;
        let bytes = data.get(at..at + face_bytes).context("cubemap is truncated")?;
        *pixels = decode_face(format, width, bytes)?;
    }
    Ok(Cubemap { size: width, faces: out })
}

fn decode_face(format: i32, size: u32, bytes: &[u8]) -> Result<Vec<[f32; 3]>> {
    let n = (size * size) as usize;
    let gamma = |v: u8| (f32::from(v) / 255.0).powf(2.2);
    let rgba8 = |order: [usize; 3], stride: usize| -> Vec<[f32; 3]> {
        bytes
            .chunks_exact(stride)
            .take(n)
            .map(|p| [gamma(p[order[0]]), gamma(p[order[1]]), gamma(p[order[2]])])
            .collect()
    };
    Ok(match format {
        0 => rgba8([0, 1, 2], 4),
        1 => rgba8([3, 2, 1], 4),
        11 => rgba8([1, 2, 3], 4),
        12 | 16 => rgba8([2, 1, 0], 4),
        2 => rgba8([0, 1, 2], 3),
        3 => rgba8([2, 1, 0], 3),
        13 | 14 | 15 | 20 => {
            let variant = match format {
                14 => texpresso::Format::Bc2,
                15 => texpresso::Format::Bc3,
                _ => texpresso::Format::Bc1,
            };
            let mut rgba = vec![0u8; n * 4];
            variant.decompress(bytes, size as usize, size as usize, &mut rgba);
            rgba.as_chunks::<4>().0.iter().map(|p| [gamma(p[0]), gamma(p[1]), gamma(p[2])]).collect()
        }
        24 => bytes
            .as_chunks::<8>()
            .0
            .iter()
            .take(n)
            .map(|p| {
                let h = |i: usize| half::f16::from_le_bytes([p[i], p[i + 1]]).to_f32().max(0.0);
                [h(0), h(2), h(4)]
            })
            .map(|c| c.map(|v| if v.is_finite() { v } else { 0.0 }))
            .collect(),
        25 => bytes
            .as_chunks::<8>()
            .0
            .iter()
            .take(n)
            .map(|p| {
                // Integer HDR: 0 to 65535 over the material system's range of 16.
                let u = |i: usize| f32::from(u16::from_le_bytes([p[i], p[i + 1]])) / 65535.0 * 16.0;
                [u(0), u(2), u(4)]
            })
            .collect(),
        _ => bail!("unsupported cubemap format {format}"),
    })
}

/// The face of `direction` (Source axes) and where on it, `s` and `t` from 0
/// to 1, `t` down the face; Direct3D's and OpenGL's cube face selection.
pub fn face_of(direction: [f32; 3]) -> (usize, f32, f32) {
    let [x, y, z] = direction;
    let (ax, ay, az) = (x.abs(), y.abs(), z.abs());
    let (face, sc, tc, ma) = if ax >= ay && ax >= az {
        if x >= 0.0 { (0, -z, -y, ax) } else { (1, z, -y, ax) }
    } else if ay >= az {
        if y >= 0.0 { (2, x, z, ay) } else { (3, x, -z, ay) }
    } else if z >= 0.0 {
        (4, x, -y, az)
    } else {
        (5, -x, -y, az)
    };
    (face, (sc / ma + 1.0) * 0.5, (tc / ma + 1.0) * 0.5)
}

impl Cubemap {
    /// The texel `direction` lands on, nearest.
    pub fn sample(&self, direction: [f32; 3]) -> [f32; 3] {
        let (face, s, t) = face_of(direction);
        let size = self.size as f32;
        let x = ((s * size) as u32).min(self.size - 1);
        let y = ((t * size) as u32).min(self.size - 1);
        self.faces[face][(y * self.size + x) as usize]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn faces_follow_the_direct3d_layout() {
        assert_eq!(face_of([1.0, 0.0, 0.0]).0, 0);
        assert_eq!(face_of([0.0, -1.0, 0.0]).0, 3);
        assert_eq!(face_of([0.0, 0.0, 1.0]).0, 4);
        // On +Z, s runs along +x and t along -y.
        let (_, s, t) = face_of([0.5, 0.5, 1.0]);
        assert!((s - 0.75).abs() < 1e-6 && (t - 0.25).abs() < 1e-6);
    }

    /// With `SRC2MC_CUBEMAP_BSP` set to a map: across every cube edge the two
    /// faces meet with the same colours, which only the right face order and
    /// orientation give.
    #[test]
    #[ignore]
    fn real_cubemaps_are_continuous_across_edges() {
        let path = std::env::var("SRC2MC_CUBEMAP_BSP").unwrap();
        let data = std::fs::read(path).unwrap();
        let entry = |i: usize| i32::from_le_bytes(data[8 + 40 * 16 + i..8 + 40 * 16 + i + 4].try_into().unwrap()) as usize;
        let pak = &data[entry(0)..entry(0) + entry(4)];
        let mut zip = zip::ZipArchive::new(std::io::Cursor::new(pak)).unwrap();
        let names: Vec<String> = zip.file_names().filter(|n| n.contains("/c") && n.ends_with(std::env::var("SRC2MC_CUBEMAP_SUFFIX").as_deref().unwrap_or(".hdr.vtf"))).map(String::from).collect();
        let (mut seam, mut random, mut count) = (0.0f64, 0.0f64, 0);
        for name in names.iter().take(40) {
            let mut bytes = Vec::new();
            std::io::Read::read_to_end(&mut zip.by_name(name).unwrap(), &mut bytes).unwrap();
            let cube = decode(&bytes).unwrap();
            let lum = |c: [f32; 3]| f64::from(c[0] + c[1] + c[2]);
            let e = 1.5 / cube.size as f32;
            for i in 0..64 {
                let a = (i as f32 / 64.0) * 1.8 - 0.9;
                // Points on the twelve edges, nudged to either side.
                for (p, q) in [
                    ([1.0, 1.0 - e, a], [1.0 - e, 1.0, a]),
                    ([1.0, -1.0 + e, a], [1.0 - e, -1.0, a]),
                    ([-1.0, 1.0 - e, a], [-1.0 + e, 1.0, a]),
                    ([1.0, a, 1.0 - e], [1.0 - e, a, 1.0]),
                    ([-1.0, a, -1.0 + e], [-1.0 + e, a, -1.0]),
                    ([a, 1.0, 1.0 - e], [a, 1.0 - e, 1.0]),
                    ([a, -1.0, -1.0 + e], [a, -1.0 + e, -1.0]),
                ] {
                    seam += (lum(cube.sample(p)) - lum(cube.sample(q))).abs();
                    random += (lum(cube.sample(p)) - lum(cube.sample([q[1], -q[2], q[0]]))).abs();
                    count += 1;
                }
            }
        }
        eprintln!("mean seam difference {:.7}, unrelated pairs {:.7}", seam / f64::from(count), random / f64::from(count));
        assert!(seam < random * 0.5);
    }
}
