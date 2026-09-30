//! Stage timings for long conversions, printed to stderr when
//! `SRC2MC_TIMINGS` is set.
//!
//! Each mark reports the time since the previous one, so marks placed after
//! each stage of a conversion show where its minutes go.

use std::sync::Mutex;
use std::time::Instant;

static LAST: Mutex<Option<Instant>> = Mutex::new(None);

fn enabled() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("SRC2MC_TIMINGS").is_some())
}

/// Report the time since the last mark as `label`, and start the next stage.
pub fn mark(label: &str) {
    if !enabled() {
        return;
    }
    let now = Instant::now();
    let mut last = LAST.lock().unwrap();
    if let Some(previous) = *last {
        eprintln!("  {:>8.2}s  {label}", (now - previous).as_secs_f64());
    }
    *last = Some(now);
}
