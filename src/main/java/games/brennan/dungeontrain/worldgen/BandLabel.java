package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.SpheresProgressionConfig;
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
    public static final BandLabel NONE = new BandLabel("", "", -1L);

    private final String band;
    private final String stage;
    private final long lap;

    private BandLabel(String band, String stage, long lap) {
        this.band = band;
        this.stage = stage;
        this.lap = lap;
    }

    /** {@code "Nether (Better Nether)"}, or empty for {@link #NONE}. */
    public String band() {
        return band;
    }

    /** {@code "6/7 Structure boost (42%)"} — see {@link BandStages}; empty on the classic order or for {@link #NONE}. */
    public String stage() {
        return stage;
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
            stageAt(cycle, worldX), cycle.cycleIndex(worldX));
    }

    /** The stage within the layout slot at {@code worldX}; empty on the classic single-period order. */
    private static String stageAt(WorldGenCycle cycle, int worldX) {
        int slot = cycle.slotIndexAt(worldX);
        if (slot < 0) return "";
        int[] mults = cycle.stageMultipliers();
        java.util.List<BandStages.Stage> stages = BandStages.of(cycle.layout(), slot,
            mults == null ? 1 : mults.length, cycle.stageBlocks(), cycle.beachBlocks(),
            SpheresProgressionConfig.segments(), SpheresProgressionConfig.exitTaperBlocks(),
            SpheresProgressionConfig.exitVoidBlocks());
        BandStages.Position at = BandStages.locate(stages, cycle.slotLocal(worldX));
        return at == null ? "" : at.describe();
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
        return o instanceof BandLabel other && other.lap == lap && other.band.equals(band)
            && other.stage.equals(stage);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * band.hashCode() + stage.hashCode()) + Long.hashCode(lap);
    }
}
