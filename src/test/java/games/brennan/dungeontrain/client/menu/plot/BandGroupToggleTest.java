package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.BandGroup;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mask arithmetic and cell geometry behind the type menu's band-group toggles. */
final class BandGroupToggleTest {

    private static final int ALL = TrainPhase.ALL_MASK;

    @Test
    @DisplayName("click: a fully-on group turns off; a partial or empty group turns fully on")
    void clickGroup() {
        assertEquals(OptionalInt.of(ALL & ~BandGroup.LEGACY.mask()),
            BandGroupToggle.clickGroup(ALL, BandGroup.LEGACY, false));
        int partial = BandGroup.CORE.mask() | TrainPhase.BETA.bit();
        assertEquals(OptionalInt.of(BandGroup.CORE.mask() | BandGroup.LEGACY.mask()),
            BandGroupToggle.clickGroup(partial, BandGroup.LEGACY, false));
        assertEquals(OptionalInt.of(BandGroup.CORE.mask() | BandGroup.PRESETS.mask()),
            BandGroupToggle.clickGroup(BandGroup.CORE.mask(), BandGroup.PRESETS, false));
    }

    @Test
    @DisplayName("shift-click solos the group")
    void shiftSolos() {
        assertEquals(OptionalInt.of(BandGroup.FRAGMENTS.mask()),
            BandGroupToggle.clickGroup(ALL, BandGroup.FRAGMENTS, true));
    }

    @Test
    @DisplayName("any change that would select no band is refused")
    void refusesEmpty() {
        assertTrue(BandGroupToggle.clickGroup(BandGroup.CORE.mask(), BandGroup.CORE, false).isEmpty());
        assertTrue(BandGroupToggle.toggleBand(TrainPhase.END.bit(), TrainPhase.END).isEmpty());
        assertTrue(BandGroupToggle.invert(ALL).isEmpty());
        assertEquals(OptionalInt.of(ALL & ~TrainPhase.END.bit()), BandGroupToggle.invert(TrainPhase.END.bit()));
    }

    @Test
    @DisplayName("slotAt round-trips every slot's centre and clamps outside the cell")
    void geometry() {
        double left = 1.0, right = 3.65;
        for (int slot = 0; slot <= BandGroupToggle.PICKER_SLOT; slot++) {
            double mid = (BandGroupToggle.slotLeft(slot, left, right) + BandGroupToggle.slotRight(slot, left, right)) / 2;
            assertEquals(slot, BandGroupToggle.slotAt(mid, left, right));
        }
        assertEquals(0, BandGroupToggle.slotAt(left - 1, left, right));
        assertEquals(BandGroupToggle.PICKER_SLOT, BandGroupToggle.slotAt(right + 1, left, right));
        assertEquals(right, BandGroupToggle.slotRight(BandGroupToggle.PICKER_SLOT, left, right), 1e-9);
    }

    @Test
    @DisplayName("TemplateGate.withPhaseMask replaces the band set and rejects an empty mask")
    void withPhaseMask() {
        TemplateGate g = TemplateGate.DEFAULT.withMinLevel(3).withPhaseMask(BandGroup.PRESETS.mask());
        assertEquals(EnumSet.of(TrainPhase.LARGE_BIOMES, TrainPhase.AMPLIFIED), g.phases());
        assertEquals(3, g.minLevel());
        assertThrows(IllegalArgumentException.class, () -> TemplateGate.DEFAULT.withPhaseMask(0));
    }

    @Test
    @DisplayName("a 'mask' literal beside the <phase> word argument wins, as the phase commands rely on")
    void maskLiteralBeatsPhaseWord() throws CommandSyntaxException {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(LiteralArgumentBuilder.<Object>literal("phase")
            .then(RequiredArgumentBuilder.<Object, String>argument("id", StringArgumentType.word())
                .then(LiteralArgumentBuilder.<Object>literal("mask")
                    .then(RequiredArgumentBuilder.<Object, Integer>argument("mask", IntegerArgumentType.integer(1, ALL))
                        .executes(c -> 1000 + IntegerArgumentType.getInteger(c, "mask"))))
                .then(RequiredArgumentBuilder.<Object, String>argument("phase", StringArgumentType.word())
                    .then(LiteralArgumentBuilder.<Object>literal("on").executes(c -> 1)))));
        assertEquals(1005, d.execute("phase x mask 5", new Object()));
        assertEquals(1, d.execute("phase x nether on", new Object()));
        assertThrows(CommandSyntaxException.class, () -> d.execute("phase x mask 0", new Object()));
    }
}
