//! Reads the project version from the repository's `VERSION` file.
//!
//! The converter and the mod ship under one version, and `VERSION` is the only
//! place it is written. Cargo's own `package.version` must be plain semver and
//! cannot carry the `DEV-` stage prefix, so it is left unset and the CLI reports
//! this value instead.

fn main() {
    println!("cargo:rerun-if-changed=VERSION");
    let version = std::fs::read_to_string("VERSION").expect("VERSION file at the repository root");
    let version = version.trim();
    assert!(!version.is_empty(), "VERSION is empty");
    println!("cargo:rustc-env=SRC2MC_VERSION={version}");
}
