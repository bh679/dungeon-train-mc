package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign rows: switching mounts within a wood, the Auto pick, the text kept in the row's NBT and
 * read back by a real sign block entity (the call every placer stamps with), and the menu's
 * shared-text edits.
 */
final class SignVariantsTest {

    private static final BlockPos CELL = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- mounts ----

    @Test
    @DisplayName("Each mount is the same wood's block, facing the same way, waterlogging kept")
    void mountSwapsKeepWoodFacingAndWater() {
        for (Block wall : List.of(Blocks.OAK_WALL_SIGN, Blocks.BAMBOO_WALL_SIGN, Blocks.CRIMSON_WALL_SIGN)) {
            BlockState start = wall.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.WATERLOGGED, true);
            for (SignVariants.Mount m : SignVariants.Mount.values()) {
                BlockState swapped = SignVariants.withMount(start, m);
                assertEquals(m, SignVariants.mountOf(swapped), wall + " → " + m);
                assertEquals(Direction.WEST, SignVariants.facing(swapped), wall + " → " + m);
                assertTrue(swapped.getValue(BlockStateProperties.WATERLOGGED), wall + " → " + m);
                assertEquals(SignVariants.withMount(swapped, SignVariants.Mount.WALL), start, "back to the wall");
            }
        }
        assertSame(Blocks.OAK_HANGING_SIGN, SignVariants.withMount(Blocks.OAK_SIGN.defaultBlockState(),
            SignVariants.Mount.HANGING).getBlock());
    }

    @Test
    @DisplayName("A standing sign's 16-step turn carries to a hanging sign; walls snap to the side")
    void rotationCarries() {
        BlockState standing = Blocks.SPRUCE_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 5);
        assertEquals(5, SignVariants.withMount(standing, SignVariants.Mount.HANGING)
            .getValue(BlockStateProperties.ROTATION_16));
        // Rotation 0 faces south; 4 west, 8 north, 12 east.
        assertEquals(Direction.NORTH, SignVariants.facing(
            Blocks.SPRUCE_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 8)));
        BlockState wall = SignVariants.withMount(
            Blocks.SPRUCE_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 12), SignVariants.Mount.WALL);
        assertEquals(Direction.EAST, wall.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    @Test
    @DisplayName("A locked row's rotation follows the swap; an options set maps across sides")
    void rowRotationFollowsSwap() {
        BlockState wall = Blocks.OAK_WALL_SIGN.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        VariantState locked = new VariantState(wall, null, 1, RotationApplier.lockToCurrent(wall));
        VariantState standing = SignVariants.withMount(locked, SignVariants.Mount.STANDING);
        assertEquals(RotationApplier.lockToCurrent(standing.state()), standing.rotation());

        int northAndEast = (1 << Direction.NORTH.ordinal()) | (1 << Direction.EAST.ordinal());
        VariantRotation compass = SignVariants.remapOptions(northAndEast, false, true);
        assertEquals((1 << 4) | (1 << 6), compass.dirMask(), "N → slot 4, E → slot 6");
        assertEquals(northAndEast, SignVariants.remapOptions(compass.dirMask(), true, false).dirMask());
        assertEquals(VariantRotation.NONE, SignVariants.remapOptions(1 << 1, true, false), "SW only → Random");
    }

    @Test
    @DisplayName("Not a sign: nothing to swap")
    void nonSignUntouched() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertFalse(SignVariants.isSign(stone));
        assertNull(SignVariants.mountOf(stone));
        assertSame(stone, SignVariants.withMount(stone, SignVariants.Mount.WALL));
        assertNull(SignVariants.sibling(stone, SignVariants.Mount.WALL));
    }

    // ---- auto ----

    @Test
    @DisplayName("Auto picks wall, then ceiling, then a side bracket, else standing")
    void autoMount() {
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 0); // faces south
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertEquals(SignVariants.Mount.STANDING, SignVariants.autoMount(world(Map.of()), CELL, sign));
        assertEquals(SignVariants.Mount.WALL,
            SignVariants.autoMount(world(Map.of(CELL.north(), stone, CELL.above(), stone)), CELL, sign));
        assertEquals(SignVariants.Mount.HANGING,
            SignVariants.autoMount(world(Map.of(CELL.above(), stone, CELL.east(), stone)), CELL, sign));
        assertEquals(SignVariants.Mount.WALL_HANGING,
            SignVariants.autoMount(world(Map.of(CELL.east(), stone)), CELL, sign));
        // A block in front of the sign is not something to hang it on.
        assertEquals(SignVariants.Mount.STANDING,
            SignVariants.autoMount(world(Map.of(CELL.south(), stone)), CELL, sign));
    }

    // ---- text + stamping ----

    private static SignVariants.Text sample() {
        SignVariants.Side front = new SignVariants.Side(
            List.of("Mind the \"gap\"", "it's \\ here", "über 🚂", ""), DyeColor.LIGHT_BLUE, true);
        SignVariants.Side back = new SignVariants.Side(List.of("", "Back", "", ""), DyeColor.RED, false);
        return new SignVariants.Text(front, back, true);
    }

    @Test
    @DisplayName("Text written into a row's NBT reads back, and other keys are kept")
    void textRoundTrip() {
        CompoundTag extra = new CompoundTag();
        extra.putString("modded", "keep me");
        CompoundTag nbt = SignVariants.withText(extra, sample());
        assertEquals("keep me", nbt.getString("modded"));
        assertEquals(sample(), SignVariants.text(nbt));
        assertEquals("Mind the \"gap\"", SignVariants.text(nbt).firstLine());
        assertFalse(SignVariants.text(SignVariants.withWaxed(nbt, false)).waxed());
        assertEquals(sample().front(), SignVariants.text(SignVariants.withWaxed(nbt, false)).front());
    }

    @Test
    @DisplayName("Lines drop control and formatting characters and cut at the cap")
    void linesClamped() {
        // The section sign (formatting codes) and the newline go; the letters stay.
        assertEquals("acb", SignVariants.clampLine("a§cb\n"));
        assertEquals(SignVariants.MAX_LINE_CHARS, SignVariants.clampLine("x".repeat(200)).length());
    }

    @Test
    @DisplayName("Wire token survives every character; junk is refused")
    void wireRoundTrip() {
        assertEquals(sample(), SignVariants.fromWire(SignVariants.toWire(sample())));
        assertTrue(SignVariants.toWire(sample()).length() < SignVariants.WIRE_MAX);
        assertNull(SignVariants.fromWire("not json"));
        assertNull(SignVariants.fromWire("{\"f\":1}"));
        assertNull(SignVariants.fromWire(""));
    }

    @Test
    @DisplayName("A stamped sign shows the row's text, a hanging sign too (the Auto swap)")
    void stampedSignShowsText() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag rowNbt = SignVariants.withText(null, sample());

        for (BlockState state : List.of(Blocks.OAK_WALL_SIGN.defaultBlockState(), Blocks.OAK_HANGING_SIGN.defaultBlockState(),
                                        Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState())) {
            SignBlockEntity be = state.is(Blocks.OAK_WALL_SIGN)
                ? new SignBlockEntity(CELL, state) : new HangingSignBlockEntity(CELL, state);
            // What SilentBlockOps.setBlockSilent does with a row's NBT.
            CompoundTag positioned = rowNbt.copy();
            positioned.putInt("x", CELL.getX());
            positioned.putInt("y", CELL.getY());
            positioned.putInt("z", CELL.getZ());
            be.loadWithComponents(positioned, registries);

            assertEquals("Mind the \"gap\"", be.getFrontText().getMessage(0, false).getString(), state.toString());
            assertEquals("über 🚂", be.getFrontText().getMessage(2, false).getString());
            assertEquals(DyeColor.LIGHT_BLUE, be.getFrontText().getColor());
            assertTrue(be.getFrontText().hasGlowingText());
            assertEquals("Back", be.getBackText().getMessage(1, false).getString());
            assertTrue(be.isWaxed());

            // Saved back off the entity (what SignMountPass carries across a swap) it still reads the same.
            CompoundTag saved = be.saveWithoutMetadata(registries);
            assertEquals(sample(), SignVariants.text(saved));
        }
    }

    @Test
    @DisplayName("A new sign row starts waxed; one captured with text keeps it")
    void newRowsWaxed() {
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState();
        CompoundTag fresh = SignVariants.defaultNbt(sign, null);
        assertNotNull(fresh);
        assertTrue(SignVariants.text(fresh).waxed());
        CompoundTag captured = SignVariants.withText(null, new SignVariants.Text(sample().front(), sample().back(), false));
        assertSame(captured, SignVariants.defaultNbt(sign, captured));
        assertNull(SignVariants.defaultNbt(Blocks.STONE.defaultBlockState(), null));
    }

    // ---- menu edits ----

    private static List<VariantState> cell() {
        return List.of(
            VariantState.of(Blocks.OAK_WALL_SIGN.defaultBlockState()),
            VariantState.of(Blocks.SPRUCE_HANGING_SIGN.defaultBlockState()),
            VariantState.of(Blocks.CHERRY_SIGN.defaultBlockState()),
            VariantState.of(Blocks.LANTERN.defaultBlockState()));
    }

    @Test
    @DisplayName("Shared text: turning it on copies this row's text; an edit then reaches every sign")
    void sharedText() {
        List<VariantState> rows = SignRowEdits.setText(cell(), 0, sample());
        rows = SignRowEdits.setShared(rows, 0, true);
        for (int i = 0; i < 3; i++) {
            assertTrue(rows.get(i).sign().shared(), "row " + i);
            assertEquals(sample(), SignVariants.text(rows.get(i).blockEntityNbt()), "row " + i);
        }
        assertTrue(rows.get(3).sign().isDefault(), "the lantern is no sign");
        assertNull(rows.get(3).blockEntityNbt());

        SignVariants.Text other = new SignVariants.Text(SignVariants.Side.BLANK,
            new SignVariants.Side(List.of("Dining car", "", "", ""), DyeColor.BLACK, false), false);
        rows = SignRowEdits.setText(rows, 2, other);
        for (int i = 0; i < 3; i++) assertEquals(other, SignVariants.text(rows.get(i).blockEntityNbt()));

        rows = SignRowEdits.setShared(rows, 1, false);
        rows = SignRowEdits.setText(rows, 1, sample());
        assertEquals(sample(), SignVariants.text(rows.get(1).blockEntityNbt()));
        assertEquals(other, SignVariants.text(rows.get(0).blockEntityNbt()), "own text no longer spreads");
        assertNull(SignRowEdits.setText(rows, 3, sample()), "not a sign row");
    }

    @Test
    @DisplayName("Mount edits swap the block or turn Auto on; a wood with no such block refuses")
    void mountEdits() {
        List<VariantState> rows = SignRowEdits.setMount(cell(), 0, SignVariants.Mount.HANGING.ordinal());
        assertSame(Blocks.OAK_HANGING_SIGN, rows.get(0).state().getBlock());
        rows = SignRowEdits.setMount(rows, 0, SignVariants.AUTO_WIRE);
        assertTrue(rows.get(0).sign().auto());
        rows = SignRowEdits.setMount(rows, 0, SignVariants.Mount.WALL.ordinal());
        assertFalse(rows.get(0).sign().auto(), "a fixed mount turns Auto off");
        assertNull(SignRowEdits.setMount(rows, 0, 9));
        assertNull(SignRowEdits.setMount(rows, 3, 0));
    }

    @Test
    @DisplayName("A sign added to a shared cell takes the cell's text and joins the sharing")
    void addedSignJoinsShared() {
        List<VariantState> shared = SignRowEdits.setShared(SignRowEdits.setText(cell(), 0, sample()), 0, true);
        VariantState added = SignRowEdits.joinShared(shared, VariantState.of(Blocks.BIRCH_SIGN.defaultBlockState()));
        assertTrue(added.sign().shared());
        assertEquals(sample(), SignVariants.text(added.blockEntityNbt()));

        VariantState alone = VariantState.of(Blocks.BIRCH_SIGN.defaultBlockState());
        assertSame(alone, SignRowEdits.joinShared(cell(), alone), "no shared cell: unchanged");
        VariantState lantern = VariantState.of(Blocks.LANTERN.defaultBlockState());
        assertSame(lantern, SignRowEdits.joinShared(shared, lantern), "not a sign: unchanged");
    }

    @Test
    @DisplayName("Auto is refused on a plot whose placer never runs the post-overlay pass")
    void autoRefusedWhereUnsupported() {
        assertNull(SignRowEdits.setMount(cell(), 0, SignVariants.AUTO_WIRE, false));
        assertNotNull(SignRowEdits.setMount(cell(), 0, SignVariants.Mount.STANDING.ordinal(), false));
    }

    @Test
    @DisplayName("Waxing a shared row waxes the cell's shared signs and keeps their text")
    void waxShared() {
        List<VariantState> rows = SignRowEdits.setShared(SignRowEdits.setText(cell(), 0, sample()), 0, true);
        rows = SignRowEdits.setWaxed(rows, 1, false);
        for (int i = 0; i < 3; i++) {
            assertFalse(SignVariants.text(rows.get(i).blockEntityNbt()).waxed());
            assertEquals(sample().front(), SignVariants.text(rows.get(i).blockEntityNbt()).front());
        }
    }

    /** A map-backed level: listed blocks, air everywhere else. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, BlockState> copy = new HashMap<>(blocks);
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return copy.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return 384;
            }

            @Override
            public int getMinBuildHeight() {
                return -64;
            }
        };
    }
}
