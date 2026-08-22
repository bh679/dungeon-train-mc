package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variant an {@link PortalRoomCopies.Kind#SINGLE} room repeats.
 *
 * <p>Three properties carry it. It must <b>round-trip</b>, since it is written by the editor and
 * read back at stamp time through the same shared serialization the block-variant sidecars use. It
 * must <b>never yield air</b>, because air in a floor plane is a hole in the plain that drops a
 * player out of the world. And its roll must be a pure function of <b>where</b>, never of
 * <i>when</i>, or walking back across the plain would find different ground.</p>
 *
 * <p>Needs a headless Minecraft bootstrap so {@link VariantState}'s {@code BlockState} resolves.</p>
 */
class PortalRoomCopiesPaletteTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(net.minecraft.world.level.block.Block block) {
        return new VariantState(block.defaultBlockState(), null);
    }

    private static PortalRoomCopiesPalette reread(PortalRoomCopiesPalette palette) {
        return PortalRoomCopiesPalette.parse(
            new StringReader(palette.toJsonText()), "test", "memory");
    }

    @Test
    @DisplayName("A multi-block palette round-trips through its file")
    void roundTrip() {
        PortalRoomCopiesPalette palette = PortalRoomCopiesPalette.of(List.of(
            of(Blocks.STONE), of(Blocks.ANDESITE), of(Blocks.COBBLESTONE)));

        PortalRoomCopiesPalette back = reread(palette);

        assertEquals(List.of("minecraft:stone", "minecraft:andesite", "minecraft:cobblestone"),
            back.blockIds(), palette.toJsonText());
    }

    @Test
    @DisplayName("A one-entry palette round-trips too — the plain held block case")
    void roundTripSingleEntry() {
        PortalRoomCopiesPalette back = reread(PortalRoomCopiesPalette.of(List.of(of(Blocks.SANDSTONE))));
        assertEquals(List.of("minecraft:sandstone"), back.blockIds());
    }

    @Test
    @DisplayName("Anything that would floor a tile with air is dropped on the way in")
    void airEntriesAreDropped() {
        // A mob entry resolves to air at stamp time by design, and the empty placeholder is the
        // editor's "this cell becomes air" sentinel. Either one in a floor plane is a hole.
        VariantState mob = new VariantState(Blocks.STONE.defaultBlockState(), null, 1,
            games.brennan.dungeontrain.editor.VariantRotation.NONE, null,
            net.minecraft.resources.ResourceLocation.parse("minecraft:zombie"));

        PortalRoomCopiesPalette palette =
            PortalRoomCopiesPalette.of(List.of(of(Blocks.STONE), mob));

        assertEquals(List.of("minecraft:stone"), palette.blockIds(),
            "a mob entry in a floor plane is a hole a player falls through");
    }

    @Test
    @DisplayName("A palette of nothing but air entries is empty, not a palette of holes")
    void allAirIsEmpty() {
        VariantState mob = new VariantState(Blocks.STONE.defaultBlockState(), null, 1,
            games.brennan.dungeontrain.editor.VariantRotation.NONE, null,
            net.minecraft.resources.ResourceLocation.parse("minecraft:zombie"));
        assertTrue(PortalRoomCopiesPalette.of(List.of(mob)).isEmpty());
    }

    @Test
    @DisplayName("The palette is capped, so the file and the packet stay bounded")
    void cappedAtMaxEntries() {
        List<VariantState> many = new ArrayList<>();
        for (int i = 0; i < PortalRoomCopiesPalette.MAX_ENTRIES + 5; i++) many.add(of(Blocks.STONE));

        assertEquals(PortalRoomCopiesPalette.MAX_ENTRIES,
            PortalRoomCopiesPalette.of(many).size());
        assertEquals(PortalRoomCopiesPalette.MAX_ENTRIES,
            PortalRoomCopiesPalette.empty().plus(many).size());
    }

    @Test
    @DisplayName("Reading is total — a malformed file stamps a room rather than failing a pair")
    void parsingIsTotal() {
        assertTrue(PortalRoomCopiesPalette.parse(new StringReader("[]"), "t", "m").isEmpty());
        assertTrue(PortalRoomCopiesPalette.parse(new StringReader("{}"), "t", "m").isEmpty());
        assertTrue(PortalRoomCopiesPalette.parse(
            new StringReader("{\"blocks\": \"nonsense\"}"), "t", "m").isEmpty());
        assertTrue(PortalRoomCopiesPalette.parse(
            new StringReader("{\"blocks\": [\"minecraft:not_a_block\"]}"), "t", "m").isEmpty());
    }

    @Test
    @DisplayName("Removing by index leaves the rest in order")
    void withoutRemovesOne() {
        PortalRoomCopiesPalette palette = PortalRoomCopiesPalette.of(List.of(
            of(Blocks.STONE), of(Blocks.ANDESITE), of(Blocks.COBBLESTONE)));

        assertEquals(List.of("minecraft:stone", "minecraft:cobblestone"),
            palette.without(1).blockIds());
        // Out-of-range is a no-op rather than an error: the index comes from a click on a panel the
        // server has no reason to trust is in sync.
        assertEquals(3, palette.without(9).size());
        assertEquals(3, palette.without(-1).size());
    }

    @Test
    @DisplayName("The roll is a pure function of where, not of when")
    void rollIsStablePerPosition() {
        PortalRoomCopiesPalette palette = PortalRoomCopiesPalette.of(List.of(
            of(Blocks.STONE), of(Blocks.ANDESITE), of(Blocks.COBBLESTONE)));
        BlockPos cell = new BlockPos(3, 0, 5);

        VariantState first = palette.resolve(cell, 1234L, 7);
        assertNotNull(first);
        // Same inputs, same answer — this is what makes a copy walked back to the copy you left.
        assertEquals(first.state(), palette.resolve(cell, 1234L, 7).state());
    }

    @Test
    @DisplayName("The variant index separates copies, so Dynamic differs where Exact agrees")
    void variantIndexSeparatesCopies() {
        List<VariantState> states = new ArrayList<>();
        for (int i = 0; i < 8; i++) states.add(of(Blocks.STONE));
        states.set(3, of(Blocks.ANDESITE));
        PortalRoomCopiesPalette palette = PortalRoomCopiesPalette.of(states);
        BlockPos cell = new BlockPos(2, 0, 2);

        // Not an assertion that any particular pair differs — the picker is a hash, so a given pair
        // may legitimately collide. What must hold is that the index reaches the roll at all.
        boolean anyDiffer = false;
        VariantState base = palette.resolve(cell, 99L, 0);
        for (int index = 1; index < 40 && !anyDiffer; index++) {
            VariantState other = palette.resolve(cell, 99L, index);
            anyDiffer = other != null && !other.state().equals(base.state());
        }
        assertTrue(anyDiffer, "the copy's identity must reach the roll or every tile would match");
    }

    @Test
    @DisplayName("Per-entry weights reach the roll — a heavy candidate wins more cells")
    void weightsAreHonoured() {
        // 1 : 50. Over a sweep of cells the heavy entry must dominate; the exact split is the
        // picker's business, but a weight that never reached it would give roughly half and half.
        PortalRoomCopiesPalette palette = PortalRoomCopiesPalette.of(List.of(
            new VariantState(Blocks.STONE.defaultBlockState(), null, 1),
            new VariantState(Blocks.ANDESITE.defaultBlockState(), null, 50)));

        int heavy = 0;
        int total = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                VariantState picked = palette.resolve(new BlockPos(x, 0, z), 42L, 3);
                if (picked.state().is(Blocks.ANDESITE)) heavy++;
                total++;
            }
        }
        assertTrue(heavy > total * 3 / 4,
            "weight 50 against weight 1 took only " + heavy + " of " + total + " cells — "
                + "the per-entry weight is not reaching the picker");
    }

    @Test
    @DisplayName("The authored facing survives — a variant is placed as it was copied")
    void rotationDataSurvivesRoundTrip() {
        // The axis is part of the BlockState, so it has to come back off disk intact before
        // RotationApplier ever sees it.
        VariantState log = new VariantState(
            Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                    net.minecraft.core.Direction.Axis.X),
            null);

        PortalRoomCopiesPalette back = reread(PortalRoomCopiesPalette.of(List.of(log)));

        assertEquals(net.minecraft.core.Direction.Axis.X,
            back.states().get(0).state()
                .getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
    }

    @Test
    @DisplayName("An empty palette resolves to nothing rather than to air")
    void emptyResolvesToNull() {
        assertNull(PortalRoomCopiesPalette.empty().resolve(BlockPos.ZERO, 1L, 1));
        assertTrue(PortalRoomCopiesPalette.empty().isEmpty());
        assertFalse(PortalRoomCopiesPalette.of(List.of(of(Blocks.STONE))).isEmpty());
    }
}
