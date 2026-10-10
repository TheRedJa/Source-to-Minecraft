//! The diagnostics an export reports about itself.

use super::*;

fn diagnostic(
    severity: metadata::Severity,
    code: &str,
    message: &str,
    context: impl IntoIterator<Item = (&'static str, String)>,
) -> metadata::Diagnostic {
    metadata::Diagnostic {
        severity,
        code: code.into(),
        message: message.into(),
        context: context.into_iter().map(|(k, v)| (k.into(), v)).collect(),
    }
}

/// Brushes too thin to voxelize are no longer drawn as props of their own:
/// their faces are in the face lump like any other and arrive as surface
/// fragments, unowned because the cells they sit in hold no block.
pub(super) fn surface_fragments(
    conversion: &crate::convert::Conversion,
) -> Option<metadata::Diagnostic> {
    let fragments = conversion.fragments.len();
    let owned = conversion
        .fragments
        .iter()
        .filter(|f| f.owner.is_some())
        .count();
    (fragments > 0).then(|| {
        diagnostic(
            metadata::Severity::Info,
            "SURFACE_FRAGMENT_SUMMARY",
            "exact visible faces cut into per-cell fragments; unowned ones sit in cells with no block",
            [
                ("fragments", fragments.to_string()),
                ("owned", owned.to_string()),
                ("unowned", (fragments - owned).to_string()),
                (
                    "faces_without_brush",
                    conversion.stats.exact_faces_unmatched.to_string(),
                ),
            ],
        )
    })
}

pub(super) fn light_occlusion(cells: usize, without_block: usize) -> Option<metadata::Diagnostic> {
    (cells > 0).then(|| {
        diagnostic(
            metadata::Severity::Info,
            "LIGHT_OCCLUSION_SUMMARY",
            "cells recorded as blocking daylight: every block of the map, and drawn geometry that holds none",
            [
                ("cells", cells.to_string()),
                ("without_block", without_block.to_string()),
            ],
        )
    })
}

pub(super) fn movers(exports: &MoverExports, ambiguous_parents: usize) -> metadata::Diagnostic {
    diagnostic(
        metadata::Severity::Info,
        "MOVER_SUMMARY",
        "moving entities cut out of the world into the mover table; empty ones had nothing to draw, collide with or carry",
        [
            ("movers", exports.movers.len().to_string()),
            ("without_blocks", exports.thin.to_string()),
            ("empty", exports.empty.to_string()),
            ("attached_props", exports.attached_props.to_string()),
            ("ambiguous_parents", ambiguous_parents.to_string()),
        ],
    )
}

pub(super) fn ambiguous_parents(targetnames: &BTreeSet<String>) -> Option<metadata::Diagnostic> {
    (!targetnames.is_empty()).then(|| {
        diagnostic(
            metadata::Severity::Warning,
            "MOVER_PARENT_AMBIGUOUS",
            "props are parented to a name several entities share; each rides on the first mover of that name in lump order",
            [(
                "targetnames",
                targetnames.iter().cloned().collect::<Vec<_>>().join(","),
            )],
        )
    })
}

pub(super) fn collision(
    collision: &crate::voxel::collision::CellCollision,
    solid_props: usize,
    prop_cells: usize,
) -> metadata::Diagnostic {
    let empty_blocks = collision
        .shapes
        .iter()
        .filter(|(cell, boxes)| boxes.is_empty() && !collision.carriers.contains(*cell))
        .count();
    diagnostic(
        metadata::Severity::Info,
        "COLLISION_SUMMARY",
        "cells that collide as something other than a full block",
        [
            ("solid_props", solid_props.to_string()),
            ("prop_cells", prop_cells.to_string()),
            ("shaped_cells", collision.shapes.len().to_string()),
            ("carriers", collision.carriers.len().to_string()),
            ("attached_pieces", collision.attached.to_string()),
            ("empty_blocks", empty_blocks.to_string()),
        ],
    )
}
