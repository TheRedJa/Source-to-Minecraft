//! Decoding Source's `.wav` sounds, and re-encoding them as Ogg Vorbis for the
//! mod bundle.
//!
//! Source plays PCM, Microsoft ADPCM and the odd IMA ADPCM file. Whether a
//! sound loops is a property of the file, not of whatever plays it: a `cue `
//! point (or failing that a `smpl` loop) marks where the loop restarts, and a
//! file without one plays once even from a looping entity.

use anyhow::{Context, Result, bail, ensure};

const FORMAT_PCM: u16 = 1;
const FORMAT_MS_ADPCM: u16 = 2;
const FORMAT_FLOAT: u16 = 3;
const FORMAT_IMA_ADPCM: u16 = 0x11;
const FORMAT_EXTENSIBLE: u16 = 0xFFFE;

/// Vorbis quality 7 on oggenc's -1..10 scale, which libvorbis takes as 0.7.
pub const VORBIS_QUALITY: f32 = 0.7;

/// Decoded sound: samples per channel, in -1..1.
#[derive(Debug, Clone, PartialEq)]
pub struct Wav {
    pub sample_rate: u32,
    pub channels: Vec<Vec<f32>>,
    /// Frame the loop restarts at; `None` for a sound that plays once.
    pub loop_start: Option<u64>,
}

impl Wav {
    pub fn frames(&self) -> u64 {
        self.channels.first().map_or(0, |c| c.len() as u64)
    }

    /// Both channels averaged into one, for a stereo file that has to be
    /// placed in the world: OpenAL cannot position a stereo source.
    pub fn downmixed(&self) -> Wav {
        let count = self.channels.len().max(1) as f32;
        let frames = self.frames() as usize;
        let mono = (0..frames)
            .map(|i| self.channels.iter().map(|c| c[i]).sum::<f32>() / count)
            .collect();
        Wav {
            sample_rate: self.sample_rate,
            channels: vec![mono],
            loop_start: self.loop_start,
        }
    }
}

struct Format {
    tag: u16,
    channels: u16,
    sample_rate: u32,
    block_align: u16,
    bits: u16,
    extra: Vec<u8>,
}

pub fn decode(bytes: &[u8]) -> Result<Wav> {
    ensure!(
        bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WAVE",
        "not a RIFF WAVE file"
    );
    let mut format = None;
    let mut data: Option<&[u8]> = None;
    let mut fact_frames = None;
    let mut cue_start = None;
    let mut sampler_start = None;
    let mut at = 12;
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = u32_at(bytes, at + 4) as usize;
        let body_start = at + 8;
        // A truncated last chunk is common in shipped files; take what is there.
        let body = &bytes[body_start..(body_start + size).min(bytes.len())];
        match id {
            b"fmt " => format = Some(parse_format(body)?),
            b"data" => data = Some(body),
            b"fact" if body.len() >= 4 => fact_frames = Some(u32_at(body, 0) as usize),
            // The first cue point's sample offset (the sixth field of 24 bytes).
            b"cue " if body.len() >= 28 && u32_at(body, 0) > 0 => {
                cue_start = Some(u32_at(body, 4 + 20) as u64)
            }
            // A sampler chunk's first loop starts 44 bytes in, after nine
            // header fields and the loop's own ID and type.
            b"smpl" if body.len() >= 36 + 24 && u32_at(body, 28) > 0 => {
                sampler_start = Some(u32_at(body, 36 + 8) as u64)
            }
            _ => {}
        }
        at = body_start + size + (size & 1);
    }
    let format = format.context("no fmt chunk")?;
    let data = data.context("no data chunk")?;
    ensure!(
        (1..=8).contains(&format.channels),
        "unsupported channel count {}",
        format.channels
    );
    ensure!(format.sample_rate > 0, "zero sample rate");
    let mut channels = match format.tag {
        FORMAT_PCM => pcm(&format, data)?,
        FORMAT_FLOAT => float(&format, data)?,
        FORMAT_MS_ADPCM => ms_adpcm(&format, data)?,
        FORMAT_IMA_ADPCM => ima_adpcm(&format, data)?,
        tag => bail!("unsupported WAV format tag {tag:#x}"),
    };
    if let Some(frames) = fact_frames.filter(|_| format.tag != FORMAT_PCM) {
        for channel in &mut channels {
            channel.truncate(frames);
        }
    }
    let frames = channels[0].len() as u64;
    ensure!(frames > 0, "no samples");
    Ok(Wav {
        sample_rate: format.sample_rate,
        channels,
        loop_start: cue_start.or(sampler_start).filter(|&start| start < frames),
    })
}

fn parse_format(body: &[u8]) -> Result<Format> {
    ensure!(body.len() >= 16, "short fmt chunk");
    let mut format = Format {
        tag: u16_at(body, 0),
        channels: u16_at(body, 2),
        sample_rate: u32_at(body, 4),
        block_align: u16_at(body, 12),
        bits: u16_at(body, 14),
        extra: Vec::new(),
    };
    if body.len() >= 18 {
        let size = (u16_at(body, 16) as usize).min(body.len() - 18);
        format.extra = body[18..18 + size].to_vec();
    }
    if format.tag == FORMAT_EXTENSIBLE {
        // The real tag is the first two bytes of the sub-format GUID.
        ensure!(format.extra.len() >= 10, "short extensible fmt chunk");
        format.tag = u16_at(&format.extra, 6);
    }
    Ok(format)
}

fn deinterleave(channels: usize, samples: impl Iterator<Item = f32>) -> Vec<Vec<f32>> {
    let mut out = vec![Vec::new(); channels];
    for (i, sample) in samples.enumerate() {
        out[i % channels].push(sample);
    }
    // Drop a trailing partial frame.
    let frames = out.iter().map(Vec::len).min().unwrap_or(0);
    for channel in &mut out {
        channel.truncate(frames);
    }
    out
}

fn pcm(format: &Format, data: &[u8]) -> Result<Vec<Vec<f32>>> {
    let channels = format.channels as usize;
    Ok(match format.bits {
        8 => deinterleave(channels, data.iter().map(|&b| (b as f32 - 128.0) / 128.0)),
        16 => deinterleave(
            channels,
            data.chunks_exact(2)
                .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0),
        ),
        24 => deinterleave(
            channels,
            data.chunks_exact(3)
                .map(|b| (i32::from_le_bytes([0, b[0], b[1], b[2]]) >> 8) as f32 / 8_388_608.0),
        ),
        32 => deinterleave(
            channels,
            data.chunks_exact(4)
                .map(|b| i32::from_le_bytes([b[0], b[1], b[2], b[3]]) as f32 / 2_147_483_648.0),
        ),
        bits => bail!("unsupported PCM bit depth {bits}"),
    })
}

fn float(format: &Format, data: &[u8]) -> Result<Vec<Vec<f32>>> {
    ensure!(format.bits == 32, "unsupported float bit depth {}", format.bits);
    Ok(deinterleave(
        format.channels as usize,
        data.chunks_exact(4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]).clamp(-1.0, 1.0)),
    ))
}

const MS_ADAPTATION: [i32; 16] = [
    230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230,
];
const MS_DEFAULT_COEFFICIENTS: [(i32, i32); 7] = [
    (256, 0),
    (512, -256),
    (0, 0),
    (192, 64),
    (240, 0),
    (460, -208),
    (392, -232),
];

fn ms_adpcm(format: &Format, data: &[u8]) -> Result<Vec<Vec<f32>>> {
    let channels = format.channels as usize;
    let block_align = format.block_align as usize;
    ensure!(block_align >= 7 * channels, "MS ADPCM block too small");
    let mut coefficients = MS_DEFAULT_COEFFICIENTS.to_vec();
    if format.extra.len() >= 4 {
        let count = u16_at(&format.extra, 2) as usize;
        if count > 0 && format.extra.len() >= 4 + count * 4 {
            coefficients = (0..count)
                .map(|i| {
                    let at = 4 + i * 4;
                    (
                        i16_at(&format.extra, at) as i32,
                        i16_at(&format.extra, at + 2) as i32,
                    )
                })
                .collect();
        }
    }
    let mut out = vec![Vec::new(); channels];
    for block in data.chunks(block_align) {
        if block.len() < 7 * channels {
            break;
        }
        let mut predictor = vec![(0i32, 0i32); channels];
        let mut delta = vec![0i32; channels];
        let mut sample1 = vec![0i32; channels];
        let mut sample2 = vec![0i32; channels];
        for c in 0..channels {
            let index = block[c] as usize;
            predictor[c] = *coefficients
                .get(index)
                .context("MS ADPCM predictor out of range")?;
            delta[c] = i16_at(block, channels + c * 2) as i32;
            sample1[c] = i16_at(block, channels * 3 + c * 2) as i32;
            sample2[c] = i16_at(block, channels * 5 + c * 2) as i32;
        }
        for c in 0..channels {
            out[c].push(sample2[c] as f32 / 32768.0);
            out[c].push(sample1[c] as f32 / 32768.0);
        }
        let nibbles = block[7 * channels..]
            .iter()
            .flat_map(|&byte| [byte >> 4, byte & 15]);
        for (i, nibble) in nibbles.enumerate() {
            let c = i % channels;
            let signed = if nibble >= 8 {
                nibble as i32 - 16
            } else {
                nibble as i32
            };
            let (c1, c2) = predictor[c];
            let predicted = (sample1[c] * c1 + sample2[c] * c2) >> 8;
            let sample = (predicted + signed * delta[c]).clamp(-32768, 32767);
            sample2[c] = sample1[c];
            sample1[c] = sample;
            delta[c] = ((MS_ADAPTATION[nibble as usize] * delta[c]) >> 8).max(16);
            out[c].push(sample as f32 / 32768.0);
        }
    }
    let frames = out.iter().map(Vec::len).min().unwrap_or(0);
    for channel in &mut out {
        channel.truncate(frames);
    }
    Ok(out)
}

const IMA_INDEX: [i32; 8] = [-1, -1, -1, -1, 2, 4, 6, 8];
const IMA_STEP: [i32; 89] = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
    73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
    494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
    2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
    10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
];

fn ima_adpcm(format: &Format, data: &[u8]) -> Result<Vec<Vec<f32>>> {
    let channels = format.channels as usize;
    let block_align = format.block_align as usize;
    ensure!(block_align >= 4 * channels, "IMA ADPCM block too small");
    let mut out = vec![Vec::new(); channels];
    for block in data.chunks(block_align) {
        if block.len() < 4 * channels {
            break;
        }
        let mut sample = vec![0i32; channels];
        let mut index = vec![0i32; channels];
        for c in 0..channels {
            sample[c] = i16_at(block, c * 4) as i32;
            index[c] = (block[c * 4 + 2] as i32).clamp(0, 88);
            out[c].push(sample[c] as f32 / 32768.0);
        }
        // Then 4-byte words per channel in turn, eight samples each, low
        // nibble first.
        let body = &block[4 * channels..];
        for group in body.chunks(4 * channels) {
            for (c, word) in group.chunks(4).enumerate() {
                for &byte in word {
                    for nibble in [byte & 15, byte >> 4] {
                        let step = IMA_STEP[index[c] as usize];
                        let mut diff = step >> 3;
                        if nibble & 4 != 0 {
                            diff += step;
                        }
                        if nibble & 2 != 0 {
                            diff += step >> 1;
                        }
                        if nibble & 1 != 0 {
                            diff += step >> 2;
                        }
                        if nibble & 8 != 0 {
                            diff = -diff;
                        }
                        sample[c] = (sample[c] + diff).clamp(-32768, 32767);
                        index[c] = (index[c] + IMA_INDEX[(nibble & 7) as usize]).clamp(0, 88);
                        out[c].push(sample[c] as f32 / 32768.0);
                    }
                }
            }
        }
    }
    let frames = out.iter().map(Vec::len).min().unwrap_or(0);
    for channel in &mut out {
        channel.truncate(frames);
    }
    Ok(out)
}

/// Encode as Ogg Vorbis at [`VORBIS_QUALITY`]. The stream serial is fixed so
/// the same sound always encodes to the same bytes, and so the same content ID.
pub fn encode_ogg(wav: &Wav) -> Result<Vec<u8>> {
    use std::num::{NonZeroU8, NonZeroU32};
    use vorbis_rs::{VorbisBitrateManagementStrategy, VorbisEncoderBuilder};
    let rate = NonZeroU32::new(wav.sample_rate).context("zero sample rate")?;
    let channels = NonZeroU8::new(wav.channels.len() as u8).context("no channels")?;
    let mut builder = VorbisEncoderBuilder::new_with_serial(rate, channels, Vec::new(), 1);
    builder.bitrate_management_strategy(VorbisBitrateManagementStrategy::QualityVbr {
        target_quality: VORBIS_QUALITY,
    });
    let mut encoder = builder.build().context("starting the Vorbis encoder")?;
    const BLOCK: usize = 1024;
    let frames = wav.frames() as usize;
    let mut start = 0;
    while start < frames {
        let end = (start + BLOCK).min(frames);
        let block: Vec<&[f32]> = wav.channels.iter().map(|c| &c[start..end]).collect();
        encoder
            .encode_audio_block(&block)
            .context("encoding Vorbis audio")?;
        start = end;
    }
    encoder.finish().context("finishing the Vorbis stream")
}

fn u16_at(bytes: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([bytes[at], bytes[at + 1]])
}

fn i16_at(bytes: &[u8], at: usize) -> i16 {
    i16::from_le_bytes([bytes[at], bytes[at + 1]])
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A RIFF WAVE file with the given fmt body, data and extra chunks.
    pub(crate) fn riff(format: &[u8], data: &[u8], extra: &[(&[u8; 4], Vec<u8>)]) -> Vec<u8> {
        let mut chunks = Vec::new();
        let mut push = |id: &[u8], body: &[u8]| {
            chunks.extend_from_slice(id);
            chunks.extend_from_slice(&(body.len() as u32).to_le_bytes());
            chunks.extend_from_slice(body);
            if body.len() % 2 == 1 {
                chunks.push(0);
            }
        };
        push(b"fmt ", format);
        push(b"data", data);
        for (id, body) in extra {
            push(*id, body);
        }
        let mut out = b"RIFF".to_vec();
        out.extend_from_slice(&(4 + chunks.len() as u32).to_le_bytes());
        out.extend_from_slice(b"WAVE");
        out.extend_from_slice(&chunks);
        out
    }

    pub(crate) fn pcm16_format(channels: u16, rate: u32) -> Vec<u8> {
        let mut f = Vec::new();
        f.extend_from_slice(&FORMAT_PCM.to_le_bytes());
        f.extend_from_slice(&channels.to_le_bytes());
        f.extend_from_slice(&rate.to_le_bytes());
        f.extend_from_slice(&(rate * 2 * channels as u32).to_le_bytes());
        f.extend_from_slice(&(2 * channels).to_le_bytes());
        f.extend_from_slice(&16u16.to_le_bytes());
        f
    }

    fn cue(offset: u32) -> Vec<u8> {
        let mut body = 1u32.to_le_bytes().to_vec();
        for value in [1, offset, u32::from_le_bytes(*b"data"), 0, 0, offset] {
            body.extend_from_slice(&value.to_le_bytes());
        }
        body
    }

    #[test]
    fn pcm16_stereo_deinterleaves_and_reads_the_cue_point() {
        let data: Vec<u8> = [0i16, 16384, -16384, 32767, 100, -100]
            .iter()
            .flat_map(|s| s.to_le_bytes())
            .collect();
        let wav = decode(&riff(&pcm16_format(2, 22050), &data, &[(b"cue ", cue(1))])).unwrap();
        assert_eq!(wav.sample_rate, 22050);
        assert_eq!(wav.frames(), 3);
        assert_eq!(wav.channels[0], [0.0, -0.5, 100.0 / 32768.0]);
        assert_eq!(wav.channels[1][0], 0.5);
        assert_eq!(wav.loop_start, Some(1));
    }

    #[test]
    fn a_file_without_cue_or_sampler_loop_plays_once() {
        let wav = decode(&riff(&pcm16_format(1, 44100), &[0, 0, 0, 0], &[])).unwrap();
        assert_eq!(wav.loop_start, None);
    }

    #[test]
    fn eight_bit_pcm_is_unsigned() {
        let mut format = pcm16_format(1, 11025);
        format[14] = 8;
        let wav = decode(&riff(&format, &[128, 255, 0], &[])).unwrap();
        assert_eq!(wav.channels[0], [0.0, 127.0 / 128.0, -1.0]);
    }

    #[test]
    fn ms_adpcm_block_header_samples_come_first() {
        // One mono block: predictor 0, delta 16, sample1 1000, sample2 500,
        // then one byte of two zero nibbles.
        let mut format = Vec::new();
        format.extend_from_slice(&FORMAT_MS_ADPCM.to_le_bytes());
        format.extend_from_slice(&1u16.to_le_bytes());
        format.extend_from_slice(&22050u32.to_le_bytes());
        format.extend_from_slice(&0u32.to_le_bytes());
        format.extend_from_slice(&8u16.to_le_bytes()); // block align
        format.extend_from_slice(&4u16.to_le_bytes());
        format.extend_from_slice(&2u16.to_le_bytes()); // cbSize
        format.extend_from_slice(&4u16.to_le_bytes()); // samples per block
        let mut block = vec![0u8];
        block.extend_from_slice(&16i16.to_le_bytes());
        block.extend_from_slice(&1000i16.to_le_bytes());
        block.extend_from_slice(&500i16.to_le_bytes());
        block.push(0x00);
        let wav = decode(&riff(&format, &block, &[])).unwrap();
        let samples: Vec<i32> = wav.channels[0]
            .iter()
            .map(|s| (s * 32768.0).round() as i32)
            .collect();
        // Coefficients (256, 0) predict the previous sample unchanged.
        assert_eq!(samples, [500, 1000, 1000, 1000]);
    }

    #[test]
    fn ogg_encoding_is_deterministic_and_starts_with_an_ogg_page() {
        let tone: Vec<f32> = (0..22050)
            .map(|i| (i as f32 * 440.0 * std::f32::consts::TAU / 22050.0).sin() * 0.5)
            .collect();
        let wav = Wav {
            sample_rate: 22050,
            channels: vec![tone],
            loop_start: None,
        };
        let first = encode_ogg(&wav).unwrap();
        assert_eq!(&first[0..4], b"OggS");
        assert_eq!(first, encode_ogg(&wav).unwrap());
    }

    #[test]
    fn downmix_averages_channels() {
        let wav = Wav {
            sample_rate: 44100,
            channels: vec![vec![1.0, 0.0], vec![0.0, 0.5]],
            loop_start: Some(1),
        };
        let mono = wav.downmixed();
        assert_eq!(mono.channels, vec![vec![0.5, 0.25]]);
        assert_eq!(mono.loop_start, Some(1));
    }
}
