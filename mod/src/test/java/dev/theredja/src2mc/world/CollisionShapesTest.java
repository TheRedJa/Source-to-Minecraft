package dev.theredja.src2mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

final class CollisionShapesTest {
    @Test void noBoxesIsNoCollision() {
        assertTrue(CollisionShapes.build(new byte[0]).isEmpty());
    }

    @Test void aFloorHangingIntoTheCellAboveReachesPastTheBlock() {
        VoxelShape shape = CollisionShapes.build(new byte[] {0, 0, 0, 16, 16, 16, 0, 16, 0, 16, 20, 16});
        assertEquals(new AABB(0, 0, 0, 1, 1.25, 1), shape.bounds());
    }

    @Test void separateBarsStaySeparate() {
        VoxelShape shape = CollisionShapes.build(new byte[] {0, 0, 0, 4, 16, 16, 12, 0, 0, 16, 16, 16});
        assertEquals(2, shape.toAabbs().size());
        assertTrue(shape.toAabbs().stream().noneMatch(box -> box.contains(0.5, 0.5, 0.5)));
    }

    /** The one-pass shape must be exactly the union vanilla's box-by-box merge gives. */
    @Test void matchesTheVanillaUnionOfItsBoxes() {
        byte[] boxes = {0, 0, 0, 16, 2, 16, 0, 16, 0, 4, 20, 16, 5, -3, 7, 9, 1, 11, 12, 2, 0, 16, 16, 3};
        VoxelShape vanilla = Shapes.empty();
        for (int i = 0; i < boxes.length; i += 6) {
            vanilla = Shapes.or(vanilla, Shapes.box(boxes[i] / 16.0, boxes[i + 1] / 16.0, boxes[i + 2] / 16.0,
                boxes[i + 3] / 16.0, boxes[i + 4] / 16.0, boxes[i + 5] / 16.0));
        }
        VoxelShape built = CollisionShapes.build(boxes);
        assertFalse(Shapes.joinIsNotEmpty(built, vanilla, BooleanOp.NOT_SAME));
        assertEquals(vanilla.bounds(), built.bounds());
    }
}
