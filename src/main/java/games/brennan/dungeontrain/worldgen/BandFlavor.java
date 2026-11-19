package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The styled occurrences of a band that a {@link TrainPhase} doesn't separate: the BetterNether and
 * Biomes O' Plenty Nether passes both read {@link TrainPhase#NETHER}, the BetterEnd and BoP End
 * passes both read {@link TrainPhase#END}, and the BoP / WWOO overworld stretches read
 * {@link TrainPhase#OVERWORLD}. A carriage-contents group member can name flavours in its
 * {@code "bands"} field to spawn only there (see
 * {@link games.brennan.dungeontrain.train.CarriageContentsGroup.Member#bands()}); {@code /dtp} reaches
 * the same columns through {@link #test}, so the editor's teleport and the gate can't disagree.
 *
 * <p>Each test pairs an in-band check with the cycle's style lookup, because the
 * {@code WorldGenCycle.is*At} methods answer for the last pass that <em>started</em>, even between
 * bands. All are pure arithmetic on the memoised cycle, cheap enough to run per carriage.</p>
 */
public enum BandFlavor {
    BETTER_NETHER("better_nether", "Better Nether"),
    BETTER_END("better_end", "Better End"),
    BOP_NETHER("bop_nether", "Biomes O' Plenty Nether"),
    BOP_END("bop_end", "Biomes O' Plenty End"),
    WWOO("wwoo", "WWOO Overworld"),
    BOP("bop", "Biomes O' Plenty Overworld");

    private final String token;
    private final String displayName;

    BandFlavor(String token, String displayName) {
        this.token = token;
        this.displayName = displayName;
    }

    /** Name shown by {@code /dtp} when no such band is found. */
    public String displayName() {
        return displayName;
    }

    /** The JSON / {@code /dtp} token, e.g. {@code "better_nether"}. */
    public String token() {
        return token;
    }

    /** The flavour for {@code raw} (case-insensitive, trimmed), or empty when it names none. */
    public static Optional<BandFlavor> byToken(String raw) {
        if (raw == null) return Optional.empty();
        String t = raw.trim().toLowerCase(Locale.ROOT);
        for (BandFlavor f : values()) {
            if (f.token.equals(t)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** True when the overworld column at {@code worldX} is in this flavour's band. */
    public boolean test(ServerLevel overworld, int worldX) {
        return switch (this) {
            case BETTER_NETHER -> NetherBand.isInNetherBand(overworld, worldX)
                && WorldGenCycle.fromConfig().isBetterNetherAt(worldX);
            case BETTER_END -> TrainPhase.phaseAt(overworld, worldX) == TrainPhase.END
                && WorldGenCycle.fromConfig().isBetterEndAt(worldX);
            case BOP_NETHER -> NetherBand.isInNetherBand(overworld, worldX)
                && WorldGenCycle.fromConfig().isBopNetherAt(worldX);
            case BOP_END -> TrainPhase.phaseAt(overworld, worldX) == TrainPhase.END
                && WorldGenCycle.fromConfig().isBopEndAt(worldX);
            case WWOO -> overworldStretch(overworld, worldX) == SecondLapOverworld.Stretch.WWOO;
            case BOP -> overworldStretch(overworld, worldX) == SecondLapOverworld.Stretch.BOP;
        };
    }

    /** Every flavour whose band holds the column at {@code worldX}; empty in a plain band. Unmodifiable. */
    public static Set<BandFlavor> at(ServerLevel overworld, int worldX) {
        EnumSet<BandFlavor> out = EnumSet.noneOf(BandFlavor.class);
        for (BandFlavor f : values()) {
            if (f.test(overworld, worldX)) out.add(f);
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    private static SecondLapOverworld.Stretch overworldStretch(ServerLevel overworld, int worldX) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return SecondLapOverworld.Stretch.VANILLA;
        return SecondLapOverworld.at(WorldGenCycle.fromConfig(), worldX);
    }
}
