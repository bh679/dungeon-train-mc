package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * The band a world-X sits in, as the F3+4 debug panel prints it: the {@link TrainPhase} name plus
 * the styled occurrence a phase doesn't separate on its own — {@code Nether (Better Nether)},
 * {@code Overworld (WWOO)}, {@code Upside Down (Reassembly)}.
 *
 * <p>Reads the same live classifiers {@code /dtp <band>} walks ({@code command.DtpTarget}), so the
 * panel and the teleport can't disagree about where a band is. Always classified against the
 * overworld, like {@code discord.RunPosition}: the bands are a property of the train's X progression.</p>
 */
public final class BandLabel {

    /** No band to report — the world has no train, so there is no cycle to be in. */
    public static final BandLabel NONE = new BandLabel("", -1L);

    private final String band;
    private final long lap;

    private BandLabel(String band, long lap) {
        this.band = band;
        this.lap = lap;
    }

    /** {@code "Nether (Better Nether)"}, or empty for {@link #NONE}. */
    public String band() {
        return band;
    }

    /** 0-based {@link WorldGenCycle#cycleIndex}, the numbering {@code /dtp <band> <distance> <lap>} takes; {@code -1} for {@link #NONE}. */
    public long lap() {
        return lap;
    }

    /** Classify {@code worldX} in {@code overworld}. Never mutates worldgen state. */
    public static BandLabel at(ServerLevel overworld, int worldX) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return NONE;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        TrainPhase phase = TrainPhase.phaseAt(overworld, worldX);
        return new BandLabel(format(phase.displayName(), styleOf(overworld, cycle, phase, worldX)),
            cycle.cycleIndex(worldX));
    }

    /** The styled occurrence at {@code worldX} within {@code phase}, or empty when it is the plain look. */
    private static String styleOf(ServerLevel overworld, WorldGenCycle cycle, TrainPhase phase, int worldX) {
        return switch (phase) {
            case NETHER -> NetherBand.isInNetherBand(overworld, worldX) && cycle.isBetterNetherAt(worldX)
                ? "Better Nether" : "";
            case END -> cycle.isBetterEndAt(worldX) ? "Better End" : "";
            case OVERWORLD -> switch (SecondLapOverworld.at(cycle, worldX)) {
                case WWOO -> "WWOO";
                case BOP -> "Biomes O' Plenty";
                case VANILLA -> UpsideDownBand.isInExitFade(overworld, worldX) ? "Reassembly" : "";
            };
            default -> UpsideDownBand.isInExitFade(overworld, worldX) ? "Reassembly" : "";
        };
    }

    /** {@code phase} alone, or {@code "phase (style)"} when there is a style. */
    static String format(String phase, String style) {
        return style == null || style.isEmpty() ? phase : phase + " (" + style + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BandLabel other && other.lap == lap && other.band.equals(band);
    }

    @Override
    public int hashCode() {
        return 31 * band.hashCode() + Long.hashCode(lap);
    }
}
