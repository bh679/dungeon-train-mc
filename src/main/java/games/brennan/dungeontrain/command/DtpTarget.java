package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One {@code /dtp <token>} destination: a command token, the name shown when it isn't found, and the
 * column test {@link BandLocator} walks. Every {@link TrainPhase} (and its {@link TrainPhase#aliases()})
 * is a target automatically, so a new band phase needs no change here; the extra targets reach the
 * styled occurrences a phase doesn't separate (the second-lap Nether/End/overworld looks and the
 * upside-down Reassembly), reading the same live classifiers the band advancements use.
 */
record DtpTarget(String token, String displayName, ColumnTest test) {

    @FunctionalInterface
    interface ColumnTest {
        boolean test(ServerLevel overworld, int worldX);
    }

    private static final List<DtpTarget> ALL = build();

    /** Every target, phases first (in enum order), then aliases, then styled occurrences. Immutable. */
    static List<DtpTarget> all() {
        return ALL;
    }

    private static List<DtpTarget> build() {
        List<DtpTarget> out = new ArrayList<>();
        for (TrainPhase phase : TrainPhase.values()) out.add(ofPhase(phase.token(), phase));
        for (Map.Entry<String, TrainPhase> alias : TrainPhase.aliases().entrySet()) {
            out.add(ofPhase(alias.getKey(), alias.getValue()));
        }
        out.add(new DtpTarget("better_nether", "Better Nether", (l, x) -> NetherBand.isInNetherBand(l, x)
                && WorldGenCycle.fromConfig().isBetterNetherAt(x)));
        out.add(new DtpTarget("better_end", "Better End", (l, x) -> TrainPhase.phaseAt(l, x) == TrainPhase.END
                && WorldGenCycle.fromConfig().isBetterEndAt(x)));
        out.add(new DtpTarget("wwoo", "WWOO Overworld", (l, x) -> overworldStretch(l, x) == SecondLapOverworld.Stretch.WWOO));
        out.add(new DtpTarget("bop", "Biomes O' Plenty Overworld", (l, x) -> overworldStretch(l, x) == SecondLapOverworld.Stretch.BOP));
        out.add(new DtpTarget("reassembly", "Reassembly", UpsideDownBand::isInExitFade));
        return List.copyOf(out);
    }

    private static DtpTarget ofPhase(String token, TrainPhase phase) {
        return new DtpTarget(token, phase.displayName(), (l, x) -> TrainPhase.phaseAt(l, x) == phase);
    }

    private static SecondLapOverworld.Stretch overworldStretch(ServerLevel overworld, int worldX) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return SecondLapOverworld.Stretch.VANILLA;
        return SecondLapOverworld.at(WorldGenCycle.fromConfig(), worldX);
    }
}
