//! `scenes/scenes.image`: every compiled choreography scene of a game in one
//! file, which is all Half-Life 2 ships (no loose `.vcd`). Layout from the
//! SDK 2013 writer (game/shared/sceneimage.cpp:369-525,
//! public/scenefilecache/SceneImageFile.h); the engine's reader is not in
//! the SDK.
//!
//! A 20-byte header (`VSIF`, version 2, scene count, string count, directory
//! offset), a table of absolute string offsets, the strings, a directory of
//! 16-byte entries sorted by the CRC-32 of the scene's normalized name, then
//! per scene a summary and its compiled data, LZMA-compressed when that made
//! it smaller.

use std::io::Cursor;

use anyhow::{Context, Result, bail, ensure};

use super::choreo::{self, StringPool};

const ID: &[u8; 4] = b"VSIF";
const VERSION: i32 = 2;
const HEADER_LEN: usize = 20;
const ENTRY_LEN: usize = 16;
/// `lzma_header_t` (public/tier1/lzmaDecoder.h:27-34): `LZMA`, actual size,
/// compressed size, then the 5 property bytes the payload follows.
const LZMA_ID: &[u8; 4] = b"LZMA";
const LZMA_HEADER_LEN: usize = 17;

pub struct SceneImage {
    bytes: Vec<u8>,
    strings: Vec<String>,
    /// Sorted by CRC, as the file stores them.
    entries: Vec<Entry>,
}

#[derive(Debug, Clone, Copy)]
struct Entry {
    crc: u32,
    offset: usize,
    length: usize,
}

impl StringPool for SceneImage {
    fn string(&self, id: i16) -> Option<&str> {
        usize::try_from(id)
            .ok()
            .and_then(|i| self.strings.get(i))
            .map(String::as_str)
    }
}

impl SceneImage {
    pub fn parse(bytes: Vec<u8>) -> Result<SceneImage> {
        ensure!(
            bytes.len() >= HEADER_LEN,
            "scene image shorter than its header"
        );
        ensure!(&bytes[0..4] == ID, "not a scene image (no VSIF id)");
        let int = |at: usize| -> Result<i32> {
            let slice = bytes
                .get(at..at + 4)
                .with_context(|| format!("scene image ends early at byte {at}"))?;
            Ok(i32::from_le_bytes(slice.try_into()?))
        };
        let version = int(4)?;
        ensure!(
            version == VERSION,
            "scene image version {version}, expected {VERSION}"
        );
        let scenes = usize::try_from(int(8)?).context("negative scene count")?;
        let strings = usize::try_from(int(12)?).context("negative string count")?;
        let directory = usize::try_from(int(16)?).context("negative directory offset")?;
        ensure!(
            strings <= i16::MAX as usize + 1,
            "scene image has {strings} strings, more than a short can name"
        );
        let mut table = Vec::with_capacity(strings);
        for i in 0..strings {
            let at = usize::try_from(int(HEADER_LEN + 4 * i)?).context("negative string offset")?;
            let tail = bytes
                .get(at..)
                .with_context(|| format!("string {i} starts past the end"))?;
            let end = tail
                .iter()
                .position(|b| *b == 0)
                .with_context(|| format!("string {i} has no terminator"))?;
            table.push(String::from_utf8_lossy(&tail[..end]).into_owned());
        }
        let mut entries = Vec::with_capacity(scenes);
        for i in 0..scenes {
            let at = directory + ENTRY_LEN * i;
            let crc = int(at)? as u32;
            let offset = usize::try_from(int(at + 4)?).context("negative scene offset")?;
            let length = usize::try_from(int(at + 8)?).context("negative scene length")?;
            ensure!(
                offset
                    .checked_add(length)
                    .is_some_and(|end| end <= bytes.len()),
                "scene {i} lies past the end of the image"
            );
            entries.push(Entry {
                crc,
                offset,
                length,
            });
        }
        ensure!(
            entries.is_sorted_by_key(|e| e.crc),
            "scene image directory is not sorted by CRC"
        );
        Ok(SceneImage {
            bytes,
            strings: table,
            entries,
        })
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// The compiled scene a `SceneFile` names (`scenes/x/y.vcd`, any case or
    /// slashes), or `None` when the image has no such scene.
    pub fn scene(&self, path: &str) -> Option<Result<choreo::Scene>> {
        let crc = crc32(normalized(path)?.as_bytes());
        let first = self.entries.partition_point(|e| e.crc < crc);
        let entry = self.entries.get(first).filter(|e| e.crc == crc)?;
        Some(self.decode(*entry))
    }

    /// Every scene in the image, in directory order.
    pub fn scenes(&self) -> impl Iterator<Item = Result<choreo::Scene>> + '_ {
        self.entries.iter().map(|entry| self.decode(*entry))
    }

    fn decode(&self, entry: Entry) -> Result<choreo::Scene> {
        let data = &self.bytes[entry.offset..entry.offset + entry.length];
        let data = decompress(data)?;
        choreo::parse_binary(&data, self)
    }
}

/// The name the image's CRC covers: lowercase, backslashes, from `scenes\`
/// on, with a `.vcd` extension (sceneimage.cpp:426-439; the server sets the
/// extension, sceneentity.cpp:3419-3422).
fn normalized(path: &str) -> Option<String> {
    let mut name = path.trim().to_ascii_lowercase().replace('/', "\\");
    let start = name.find("scenes\\")?;
    name.drain(..start);
    let stem_end = name.rfind('.').filter(|dot| !name[*dot..].contains('\\'));
    if let Some(dot) = stem_end {
        name.truncate(dot);
    }
    name.push_str(".vcd");
    Some(name)
}

/// LZMA-compressed scene data, or the data as it is.
fn decompress(data: &[u8]) -> Result<std::borrow::Cow<'_, [u8]>> {
    if data.len() < LZMA_HEADER_LEN || &data[0..4] != LZMA_ID {
        return Ok(data.into());
    }
    let actual = u32::from_le_bytes(data[4..8].try_into()?) as u64;
    let packed = u32::from_le_bytes(data[8..12].try_into()?) as usize;
    if data.len() < LZMA_HEADER_LEN + packed {
        bail!("LZMA scene data shorter than its header says");
    }
    // The 5 property bytes are the head of a standard LZMA header; the size
    // that would follow them is given instead.
    let mut output = Vec::with_capacity(actual.min(1 << 24) as usize);
    lzma_rs::lzma_decompress_with_options(
        &mut Cursor::new(&data[12..]),
        &mut output,
        &lzma_rs::decompress::Options {
            unpacked_size: lzma_rs::decompress::UnpackedSize::UseProvided(Some(actual)),
            allow_incomplete: false,
            memlimit: None,
        },
    )
    .map_err(|e| anyhow::anyhow!("LZMA scene data: {e:?}"))?;
    Ok(output.into())
}

/// CRC-32 as tier1's `CRC32_ProcessSingleBuffer`: reflected polynomial
/// 0xEDB88320, initial and final XOR 0xFFFFFFFF.
fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0u32;
    for byte in bytes {
        crc ^= *byte as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xEDB8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::vfs::Vfs;

    #[test]
    fn crc_matches_the_standard_check_value() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }

    #[test]
    fn names_normalize_as_the_writer_does() {
        assert_eq!(
            normalized("Scenes/Trainyard/cit_water.VCD").as_deref(),
            Some("scenes\\trainyard\\cit_water.vcd")
        );
        assert_eq!(
            normalized("scenes\\k_lab\\teleport01").as_deref(),
            Some("scenes\\k_lab\\teleport01.vcd")
        );
        assert_eq!(normalized("music/x.wav"), None);
    }

    /// Every scene in Half-Life 2's image reads, and the trainstation
    /// scenes are found by name.
    #[test]
    fn reads_every_half_life_2_scene() {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_01.bsp",
        );
        if !map.exists() {
            return;
        }
        let vfs = Vfs::for_map(map, &[]);
        let image = SceneImage::parse(vfs.open("scenes/scenes.image").unwrap()).unwrap();
        assert!(image.len() > 1000, "{} scenes", image.len());
        let mut speaks = 0;
        for scene in image.scenes() {
            let scene = scene.unwrap();
            speaks += scene.to_vcd().events.len();
        }
        assert!(speaks > 1000, "{speaks} kept events");
        for name in [
            "scenes/trainyard/cop_cupcop_intro.vcd",
            "scenes/k_lab/teleport01.vcd",
            "scenes/breencast/welcome.vcd",
            "scenes/npc/gman/gman_intro.vcd",
            "scenes/trainyard/female01/cit_chainlink_respond.vcd",
        ] {
            let scene = image
                .scene(name)
                .unwrap_or_else(|| panic!("{name} missing"))
                .unwrap();
            assert!(!scene.actors.is_empty(), "{name} has no actors");
        }
    }
}
