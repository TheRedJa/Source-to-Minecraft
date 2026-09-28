package dev.theredja.src2mc.world;

import java.util.Arrays;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

/**
 * A collision shape built straight from its boxes in one pass.
 *
 * {@code Shapes.or} merges one box at a time, and each merge rebuilds the whole voxel grid of
 * everything so far: a sloped terrain cell of 250 boxes cost seconds, and the 14,655 distinct
 * shapes of the INFRA furnace 55 s, paid as lag wherever a new area was walked into. Here the
 * grid lines are the boxes' own edges, set once, and each box fills its cells of the grid --
 * the same representation vanilla's join produces, reached directly. The only way to that
 * constructor is a subclass; the shape behaves as the vanilla class it extends.
 */
final class TableShape extends ArrayVoxelShape {
    private TableShape(DiscreteVoxelShape shape, double[] xs, double[] ys, double[] zs) {
        super(shape, xs, ys, zs);
    }

    /** Boxes as six signed sixteenths each; at least one box. */
    static TableShape of(byte[] boxes) {
        int[][] edges = new int[3][];
        for (int axis = 0; axis < 3; axis++) {
            int[] values = new int[boxes.length / 3];
            for (int b = 0, n = 0; b < boxes.length; b += 6) {
                values[n++] = boxes[b + axis];
                values[n++] = boxes[b + axis + 3];
            }
            edges[axis] = Arrays.stream(values).sorted().distinct().toArray();
        }
        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(
            edges[0].length - 1, edges[1].length - 1, edges[2].length - 1);
        for (int b = 0; b < boxes.length; b += 6) {
            int x0 = Arrays.binarySearch(edges[0], boxes[b]), x1 = Arrays.binarySearch(edges[0], boxes[b + 3]);
            int y0 = Arrays.binarySearch(edges[1], boxes[b + 1]), y1 = Arrays.binarySearch(edges[1], boxes[b + 4]);
            int z0 = Arrays.binarySearch(edges[2], boxes[b + 2]), z1 = Arrays.binarySearch(edges[2], boxes[b + 5]);
            for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) discrete.fill(x, y, z);
        }
        return new TableShape(discrete, coordinates(edges[0]), coordinates(edges[1]), coordinates(edges[2]));
    }

    private static double[] coordinates(int[] sixteenths) {
        double[] out = new double[sixteenths.length];
        for (int i = 0; i < out.length; i++) out[i] = sixteenths[i] / 16.0;
        return out;
    }
}
