package dev.theredja.src2mc.world;

import dev.theredja.src2mc.Src2mc;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Fixed registry footprint; bundle reloads never add registry entries. */
public final class Src2mcWorldContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Src2mc.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Src2mc.MOD_ID);

    // Both take their collision from the map's table, which depends on the position, so neither
    // may have its shape cached per state. Forced solid: a dynamic shape would otherwise leave
    // them non-solid to fluids and spawning, which a wall of the map is not.
    public static final DeferredBlock<Src2mcSurfaceBlock> SURFACE = BLOCKS.registerBlock(
        "surface", Src2mcSurfaceBlock::new, properties().dynamicShape().forceSolidOn());
    public static final DeferredBlock<Src2mcCarrierBlock> CARRIER = BLOCKS.registerBlock(
        "carrier", Src2mcCarrierBlock::new, properties().dynamicShape().forceSolidOn().noOcclusion().replaceable());
    // A moving entity's cells, inside a Sable plot. Unbreakable: movers are not editable yet.
    public static final DeferredBlock<Src2mcMoverBlock> MOVER = BLOCKS.registerBlock(
        "mover", Src2mcMoverBlock::new, BlockBehaviour.Properties.of().strength(-1.0F, 3_600_000.0F).noLootTable()
            .sound(SoundType.STONE).dynamicShape().forceSolidOn().noOcclusion());
    public static final DeferredBlock<Src2mcDataBlock> MAP_ANCHOR = BLOCKS.registerBlock(
        "map_anchor",
        props -> new Src2mcDataBlock(props, Src2mcWorldContent::newDataBlockEntity, false, true),
        properties()
    );
    public static final DeferredBlock<Src2mcDataBlock> PROP_ROOT = BLOCKS.registerBlock(
        "prop_root",
        props -> new Src2mcDataBlock(props, Src2mcWorldContent::newDataBlockEntity, true, true, true),
        properties().dynamicShape()
    );
    public static final DeferredBlock<Src2mcDataBlock> PLACEHOLDER = BLOCKS.registerBlock(
        "placeholder",
        props -> new Src2mcDataBlock(props, Src2mcWorldContent::newDataBlockEntity, false, false),
        properties()
    );

    public static final Supplier<BlockEntityType<Src2mcDataBlockEntity>> MAP_ANCHOR_ENTITY =
        BLOCK_ENTITIES.register("map_anchor", () -> BlockEntityType.Builder.of(
            Src2mcWorldContent::newDataBlockEntity,
            MAP_ANCHOR.get()
        ).build(null));
    public static final Supplier<BlockEntityType<Src2mcDataBlockEntity>> PROP_ROOT_ENTITY =
        BLOCK_ENTITIES.register("prop_root", () -> BlockEntityType.Builder.of(
            Src2mcWorldContent::newDataBlockEntity,
            PROP_ROOT.get()
        ).build(null));
    public static final Supplier<BlockEntityType<Src2mcDataBlockEntity>> PLACEHOLDER_ENTITY =
        BLOCK_ENTITIES.register("placeholder", () -> BlockEntityType.Builder.of(
            Src2mcWorldContent::newDataBlockEntity,
            PLACEHOLDER.get()
        ).build(null));

    private Src2mcWorldContent() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
        Src2mcSounds.register(bus);
    }

    private static Src2mcDataBlockEntity newDataBlockEntity(BlockPos position, BlockState state) {
        BlockEntityType<?> type;
        if (state.is(MAP_ANCHOR.get())) {
            type = MAP_ANCHOR_ENTITY.get();
        } else if (state.is(PROP_ROOT.get())) {
            type = PROP_ROOT_ENTITY.get();
        } else if (state.is(PLACEHOLDER.get())) {
            type = PLACEHOLDER_ENTITY.get();
        } else {
            throw new IllegalArgumentException("src2mc data block has no block-entity type: " + state);
        }
        return new Src2mcDataBlockEntity(type, position, state);
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE);
    }
}
