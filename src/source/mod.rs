//! Reading Source's own content: material definitions, textures, models and
//! sounds.
//!
//! Everything here is optional. The converter works without a game install by
//! matching the average colour the compiler baked into the BSP; this module is
//! what turns that into the real texture.

pub mod ain;
pub mod anim;
pub mod captions;
pub mod credits;
pub mod choreo;
pub mod cubemap;
pub mod extract;
pub mod infra;
pub mod keyvalues;
pub mod kvfile;
pub mod mdl;
pub mod mp3;
pub mod pcf;
pub mod report;
pub mod scene_image;
pub mod sound;
pub mod titles;
pub mod vcd;
pub mod vfs;
pub mod vmt;
pub mod vtf;
pub mod wav;
