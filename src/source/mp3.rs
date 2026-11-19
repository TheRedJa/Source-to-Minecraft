//! Decoding `.mp3` sounds (Half-Life 2 ships its music as MP3) into the same
//! [`Wav`] the `.wav` decoder produces. An MP3 has no loop points, so it
//! plays once.

use std::io::Cursor;

use anyhow::{Context, Result, anyhow, ensure};
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::errors::Error;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

use super::wav::Wav;

pub fn decode(bytes: &[u8]) -> Result<Wav> {
    let stream = MediaSourceStream::new(Box::new(Cursor::new(bytes.to_vec())), Default::default());
    let mut hint = Hint::new();
    hint.with_extension("mp3");
    let probed = symphonia::default::get_probe()
        .format(
            &hint,
            stream,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )
        .map_err(|e| anyhow!("not an MP3 stream: {e}"))?;
    let mut format = probed.format;
    let track = format.default_track().context("MP3 has no audio track")?;
    let track_id = track.id;
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|e| anyhow!("MP3 codec: {e}"))?;
    let mut sample_rate = 0;
    let mut channels: Vec<Vec<f32>> = Vec::new();
    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(Error::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(anyhow!("MP3 stream: {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        let decoded = match decoder.decode(&packet) {
            Ok(decoded) => decoded,
            // A damaged frame is skipped, as players do.
            Err(Error::DecodeError(_)) => continue,
            Err(e) => return Err(anyhow!("MP3 frame: {e}")),
        };
        let spec = *decoded.spec();
        let frames = decoded.frames();
        if channels.is_empty() {
            sample_rate = spec.rate;
            channels = vec![Vec::new(); spec.channels.count()];
        }
        ensure!(
            spec.channels.count() == channels.len() && spec.rate == sample_rate,
            "MP3 changes its channel count or rate mid-stream"
        );
        let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
        buffer.copy_planar_ref(decoded);
        let samples = buffer.samples();
        for (c, channel) in channels.iter_mut().enumerate() {
            channel.extend_from_slice(&samples[c * frames..(c + 1) * frames]);
        }
    }
    ensure!(
        !channels.is_empty() && sample_rate > 0,
        "MP3 holds no audio"
    );
    Ok(Wav {
        sample_rate,
        channels,
        loop_start: None,
    })
}

#[cfg(test)]
mod tests {
    use crate::source::vfs::Vfs;

    /// Half-Life 2's trainstation music decodes to its full length.
    #[test]
    fn decodes_half_life_2_music() {
        let map = std::path::Path::new(
            "/mnt/games/SteamLibrary/steamapps/common/Half-Life 2/hl2/maps/d1_trainstation_01.bsp",
        );
        if !map.exists() {
            return;
        }
        let vfs = Vfs::for_map(map, &[]);
        let bytes = vfs
            .open("sound/music/hl2_song26_trainstation1.mp3")
            .unwrap();
        let wav = super::decode(&bytes).unwrap();
        let seconds = wav.frames() as f64 / wav.sample_rate as f64;
        assert!((60.0..200.0).contains(&seconds), "{seconds} s");
        assert!(wav.channels.iter().all(|c| c.len() as u64 == wav.frames()));
    }
}
