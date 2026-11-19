//! Compiled choreography scenes: the binary `.vcd` form (`bvcd`, version 4)
//! that `scenes.image` stores, read field for field as
//! `CChoreoScene::RestoreFromBinaryBuffer` reads it (SDK 2013
//! game/shared/choreoscene.cpp, choreoactor.cpp, choreochannel.cpp,
//! choreoevent.cpp). Every event is kept with everything the file stores;
//! [`Scene::to_vcd`] narrows it to what the logic table carries today, by the
//! same rules as the text parser in [`super::vcd`].

use anyhow::{Result, bail, ensure};

use super::vcd;

/// `MAKEID('b','v','c','d')`: the bytes `bvcd` read as a little-endian int.
const BINARY_TAG: &[u8; 4] = b"bvcd";
const BINARY_VERSION: u8 = 4;
/// Times past this are garbage, not a scene anyone authored (as `vcd`).
const MAX_TIME: f64 = 86_400.0;
/// `OnTrigger1` to `OnTrigger16` exist on a scene entity.
const MAX_TRIGGER: i64 = 16;

/// `CChoreoEvent::EVENTTYPE` (choreoevent.h:257-312).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventType {
    Unspecified,
    Section,
    Expression,
    LookAt,
    MoveTo,
    Speak,
    Gesture,
    Sequence,
    Face,
    FireTrigger,
    FlexAnimation,
    SubScene,
    Loop,
    Interrupt,
    StopPoint,
    PermitResponses,
    Generic,
}

impl EventType {
    fn from_byte(byte: u8) -> Option<EventType> {
        use EventType::*;
        Some(match byte {
            0 => Unspecified,
            1 => Section,
            2 => Expression,
            3 => LookAt,
            4 => MoveTo,
            5 => Speak,
            6 => Gesture,
            7 => Sequence,
            8 => Face,
            9 => FireTrigger,
            10 => FlexAnimation,
            11 => SubScene,
            12 => Loop,
            13 => Interrupt,
            14 => StopPoint,
            15 => PermitResponses,
            16 => Generic,
            _ => return None,
        })
    }
}

/// `CChoreoEvent::CLOSECAPTION` (choreoevent.h:329-331).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaptionType {
    Master,
    Slave,
    Disabled,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Scene {
    /// Events outside any actor.
    pub events: Vec<Event>,
    pub actors: Vec<Actor>,
    pub ramp: Vec<CurveSample>,
    pub ignore_phonemes: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Actor {
    pub name: String,
    pub channels: Vec<Channel>,
    pub active: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Channel {
    pub name: String,
    pub events: Vec<Event>,
    pub active: bool,
}

/// A curve point; `value` is stored as a byte, 0..1.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CurveSample {
    pub time: f32,
    pub value: f32,
}

/// A named tag at a fraction of the event.
#[derive(Debug, Clone, PartialEq)]
pub struct Tag {
    pub name: String,
    pub fraction: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FlexSample {
    pub time: f32,
    pub value: f32,
    pub curve_type: u16,
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlexTrack {
    pub name: String,
    pub active: bool,
    pub min: f32,
    pub max: f32,
    pub samples: Vec<FlexSample>,
    /// Present for a combo track (its left/right balance curve).
    pub combo: Option<Vec<FlexSample>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Caption {
    pub kind: CaptionType,
    pub token: String,
    pub combined_file: bool,
    pub gender_token: bool,
    pub suppress_attenuation: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Event {
    pub kind: EventType,
    pub name: String,
    pub start: f32,
    /// `-1` when the event has no end.
    pub end: f32,
    pub params: [String; 3],
    pub ramp: Vec<CurveSample>,
    pub resume_condition: bool,
    pub lock_body_facing: bool,
    pub fixed_length: bool,
    pub active: bool,
    pub force_short_movement: bool,
    pub play_over_script: bool,
    pub distance_to_target: f32,
    pub relative_tags: Vec<Tag>,
    pub timing_tags: Vec<Tag>,
    /// `PLAYBACK` and `ORIGINAL` absolute tags.
    pub absolute_tags: [Vec<Tag>; 2],
    /// Gesture events only: the sequence's duration, when it was known.
    pub gesture_duration: Option<f32>,
    /// (tag name, wav name) the event is placed relative to.
    pub relative_to: Option<(String, String)>,
    pub flex_tracks: Vec<FlexTrack>,
    /// Loop events only.
    pub loop_count: Option<i8>,
    /// Speak events only.
    pub caption: Option<Caption>,
}

/// Resolves the string-pool ids a compiled scene stores.
pub trait StringPool {
    /// The string, or `None` for an id outside the pool.
    fn string(&self, id: i16) -> Option<&str>;
}

/// Read a compiled scene.
pub fn parse_binary(bytes: &[u8], pool: &dyn StringPool) -> Result<Scene> {
    let mut r = Reader { bytes, at: 0, pool };
    ensure!(
        r.take(4)? == BINARY_TAG,
        "not a compiled scene (no bvcd tag)"
    );
    let version = r.u8()?;
    ensure!(
        version == BINARY_VERSION,
        "compiled scene version {version}, expected {BINARY_VERSION}"
    );
    // The CRC of the text the scene was compiled from.
    r.take(4)?;
    let mut events = Vec::new();
    for _ in 0..r.u8()? {
        events.push(r.event()?);
    }
    let mut actors = Vec::new();
    for _ in 0..r.u8()? {
        actors.push(r.actor()?);
    }
    let ramp = r.curve()?;
    let ignore_phonemes = r.u8()? != 0;
    Ok(Scene {
        events,
        actors,
        ramp,
        ignore_phonemes,
    })
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
    pool: &'a dyn StringPool,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8]> {
        let Some(slice) = self.bytes.get(self.at..self.at + n) else {
            bail!("compiled scene ends early at byte {}", self.at);
        };
        self.at += n;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn i8(&mut self) -> Result<i8> {
        Ok(self.u8()? as i8)
    }

    fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_le_bytes(self.take(2)?.try_into()?))
    }

    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into()?))
    }

    fn f32(&mut self) -> Result<f32> {
        Ok(f32::from_le_bytes(self.take(4)?.try_into()?))
    }

    fn string(&mut self) -> Result<String> {
        let id = self.i16()?;
        match self.pool.string(id) {
            Some(s) => Ok(s.to_string()),
            None => bail!("compiled scene names string {id}, outside the pool"),
        }
    }

    fn actor(&mut self) -> Result<Actor> {
        let name = self.string()?;
        let mut channels = Vec::new();
        for _ in 0..self.u8()? {
            let name = self.string()?;
            let mut events = Vec::new();
            for _ in 0..self.u8()? {
                events.push(self.event()?);
            }
            let active = self.i8()? == 1;
            channels.push(Channel {
                name,
                events,
                active,
            });
        }
        let active = self.i8()? == 1;
        Ok(Actor {
            name,
            channels,
            active,
        })
    }

    fn curve(&mut self) -> Result<Vec<CurveSample>> {
        let n = self.u8()?;
        let mut samples = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let time = self.f32()?;
            let value = self.u8()? as f32 / 255.0;
            samples.push(CurveSample { time, value });
        }
        Ok(samples)
    }

    fn tags(&mut self) -> Result<Vec<Tag>> {
        let n = self.u8()?;
        let mut tags = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let name = self.string()?;
            let fraction = self.u8()? as f32 / 255.0;
            tags.push(Tag { name, fraction });
        }
        Ok(tags)
    }

    fn flex_samples(&mut self, n: usize) -> Result<Vec<FlexSample>> {
        let mut samples = Vec::with_capacity(n);
        for _ in 0..n {
            let time = self.f32()?;
            let value = self.u8()? as f32 / 255.0;
            let curve_type = self.u16()?;
            samples.push(FlexSample {
                time,
                value,
                curve_type,
            });
        }
        Ok(samples)
    }

    fn event(&mut self) -> Result<Event> {
        let byte = self.u8()?;
        let Some(kind) = EventType::from_byte(byte) else {
            bail!("compiled scene event type {byte} is unknown");
        };
        let name = self.string()?;
        let start = self.f32()?;
        let end = self.f32()?;
        let params = [self.string()?, self.string()?, self.string()?];
        let ramp = self.curve()?;
        let flags = self.u8()?;
        let distance_to_target = self.f32()?;
        let relative_tags = self.tags()?;
        let timing_tags = self.tags()?;
        let mut absolute_tags = [Vec::new(), Vec::new()];
        for tags in &mut absolute_tags {
            for _ in 0..self.u8()? {
                let name = self.string()?;
                let fraction = self.u16()? as f32 / 4096.0;
                tags.push(Tag { name, fraction });
            }
        }
        let gesture_duration = if kind == EventType::Gesture {
            Some(self.f32()?).filter(|d| *d != -1.0)
        } else {
            None
        };
        let relative_to = if self.i8()? == 1 {
            Some((self.string()?, self.string()?))
        } else {
            None
        };
        let mut flex_tracks = Vec::new();
        for _ in 0..self.u8()? {
            let name = self.string()?;
            let track_flags = self.u8()?;
            let min = self.f32()?;
            let max = self.f32()?;
            // `GetShort` into an int: a negative count reads no samples.
            let count = self.i16()?.max(0) as usize;
            let samples = self.flex_samples(count)?;
            let combo = if track_flags & 2 != 0 {
                let count = self.u16()? as usize;
                Some(self.flex_samples(count)?)
            } else {
                None
            };
            flex_tracks.push(FlexTrack {
                name,
                active: track_flags & 1 != 0,
                min,
                max,
                samples,
                combo,
            });
        }
        let loop_count = if kind == EventType::Loop {
            Some(self.i8()?)
        } else {
            None
        };
        let caption = if kind == EventType::Speak {
            let kind = match self.i8()? {
                0 => CaptionType::Master,
                1 => CaptionType::Slave,
                2 => CaptionType::Disabled,
                other => bail!("speak event caption type {other} is unknown"),
            };
            let token = self.string()?;
            let flags = self.i8()?;
            Some(Caption {
                kind,
                token,
                combined_file: flags & 1 != 0,
                gender_token: flags & 2 != 0,
                suppress_attenuation: flags & 4 != 0,
            })
        } else {
            None
        };
        Ok(Event {
            kind,
            name,
            start,
            end,
            params,
            ramp,
            resume_condition: flags & 1 != 0,
            lock_body_facing: flags & 2 != 0,
            fixed_length: flags & 4 != 0,
            active: flags & 8 != 0,
            force_short_movement: flags & 16 != 0,
            play_over_script: flags & 32 != 0,
            distance_to_target,
            relative_tags,
            timing_tags,
            absolute_tags,
            gesture_duration,
            relative_to,
            flex_tracks,
            loop_count,
            caption,
        })
    }
}

impl Scene {
    /// Every event with the (lowercase) name of the actor it belongs to,
    /// `""` for events outside any actor.
    pub fn all_events(&self) -> impl Iterator<Item = (String, &Event)> {
        self.events
            .iter()
            .map(|e| (String::new(), e))
            .chain(self.actors.iter().flat_map(|actor| {
                let name = actor.name.to_ascii_lowercase();
                actor
                    .channels
                    .iter()
                    .flat_map(|channel| &channel.events)
                    .map(move |e| (name.clone(), e))
            }))
    }

    /// The speak and firetrigger events and the scene's length, by exactly
    /// the rules `vcd::parse` applies to a text scene.
    pub fn to_vcd(&self) -> vcd::Scene {
        let mut length = 0.0f64;
        let mut events = Vec::new();
        for (actor, event) in self.all_events() {
            let (start, end) = (event.start as f64, event.end as f64);
            let valid = |t: f64| t.is_finite() && t.abs() <= MAX_TIME;
            if !valid(start) || !valid(end) {
                continue;
            }
            let start = start.max(0.0);
            let latest = if end < 0.0 { start } else { end.max(start) };
            length = length.max(latest);
            let param = event.params[0].trim();
            match event.kind {
                EventType::Speak if !param.is_empty() => {
                    let caption = event.caption.as_ref();
                    events.push(vcd::Event::Speak {
                        actor,
                        start,
                        end: latest,
                        script: param.to_string(),
                        cctoken: caption.map_or(String::new(), |c| c.token.trim().to_string()),
                        caption_disabled: caption.is_some_and(|c| c.kind == CaptionType::Disabled),
                    })
                }
                EventType::FireTrigger => {
                    if let Ok(trigger) = param.parse::<i64>()
                        && (1..=MAX_TRIGGER).contains(&trigger)
                    {
                        events.push(vcd::Event::FireTrigger {
                            start,
                            trigger: trigger as u8,
                        });
                    }
                }
                _ => {}
            }
        }
        events.sort_by(|a, b| a.start().total_cmp(&b.start()));
        vcd::Scene { length, events }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Pool(Vec<&'static str>);

    impl StringPool for Pool {
        fn string(&self, id: i16) -> Option<&str> {
            usize::try_from(id)
                .ok()
                .and_then(|i| self.0.get(i))
                .copied()
        }
    }

    /// Writes events the way `CChoreoEvent::SaveToBuffer` does.
    struct Writer(Vec<u8>);

    impl Writer {
        fn u8(&mut self, v: u8) -> &mut Self {
            self.0.push(v);
            self
        }
        fn i16(&mut self, v: i16) -> &mut Self {
            self.0.extend(v.to_le_bytes());
            self
        }
        fn f32(&mut self, v: f32) -> &mut Self {
            self.0.extend(v.to_le_bytes());
            self
        }
        /// An event with no ramp, tags or flex tracks.
        fn plain_event(&mut self, kind: u8, name: i16, start: f32, end: f32, p1: i16) -> &mut Self {
            self.u8(kind)
                .i16(name)
                .f32(start)
                .f32(end)
                .i16(p1)
                .i16(0)
                .i16(0);
            self.u8(0); // ramp
            self.u8(8); // flags: active
            self.f32(0.0); // distance to target
            self.u8(0).u8(0).u8(0).u8(0); // relative, timing, 2x absolute tags
            if kind == 6 {
                self.f32(-1.0);
            }
            self.u8(0); // not relative
            self.u8(0); // flex tracks
            if kind == 12 {
                self.u8(2);
            }
            self
        }
    }

    #[test]
    fn reads_actors_channels_and_events() {
        // 0 "", 1 "!target1", 2 "talk", 3 "vo.line", 4 "#cc", 5 "2", 6 "trig"
        let pool = Pool(vec!["", "!Target1", "talk", "vo.line", "#cc", "2", "trig"]);
        let mut w = Writer(b"bvcd".to_vec());
        w.u8(4).i16(0).i16(0); // version, CRC
        w.u8(1).plain_event(9, 6, 1.5, -1.0, 5); // scene-level firetrigger
        w.u8(1).i16(1).u8(1).i16(2).u8(2); // actor, 1 channel, 2 events
        w.plain_event(5, 2, 0.25, 2.0, 3);
        w.u8(0).i16(4).u8(0); // speak: master caption, token, flags
        w.plain_event(6, 2, 3.0, 4.5, 2);
        w.u8(1).u8(1); // channel active, actor active
        w.u8(1).f32(0.5).u8(255); // scene ramp
        w.u8(0); // phonemes not ignored
        let scene = parse_binary(&w.0, &pool).unwrap();
        assert_eq!(scene.events.len(), 1);
        assert_eq!(scene.actors[0].channels[0].events.len(), 2);
        assert_eq!(
            scene.ramp,
            vec![CurveSample {
                time: 0.5,
                value: 1.0
            }]
        );
        let gesture = &scene.actors[0].channels[0].events[1];
        assert_eq!(gesture.kind, EventType::Gesture);
        assert_eq!(gesture.gesture_duration, None);
        assert!(gesture.active);

        let narrowed = scene.to_vcd();
        assert_eq!(narrowed.length, 4.5);
        assert_eq!(
            narrowed.events,
            vec![
                vcd::Event::Speak {
                    actor: "!target1".into(),
                    start: 0.25,
                    end: 2.0,
                    script: "vo.line".into(),
                    cctoken: "#cc".into(),
                    caption_disabled: false,
                },
                vcd::Event::FireTrigger {
                    start: 1.5,
                    trigger: 2
                },
            ]
        );
    }

    #[test]
    fn rejects_truncated_and_foreign_data() {
        let pool = Pool(vec![""]);
        assert!(parse_binary(b"VCD!", &pool).is_err());
        assert!(parse_binary(b"bvcd\x04\0\0\0\0\x01", &pool).is_err());
        assert!(parse_binary(b"bvcd\x03\0\0\0\0\0\0\0\0", &pool).is_err());
    }
}
