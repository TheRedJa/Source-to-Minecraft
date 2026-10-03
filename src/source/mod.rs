//! Reading Source's own content: material definitions, textures, models and
//! sounds.
//!
//! Everything here is optional. The converter works without a game install by
//! matching the average colour the compiler baked into the BSP; this module is
//! what turns that into the real texture.

pub mod captions;
pub mod extract;
pub mod infra;
pub mod keyvalues;
pub mod mdl;
pub mod report;
pub mod sound;
pub mod vcd;
pub mod vfs;
pub mod vmt;
pub mod vtf;
pub mod wav;
