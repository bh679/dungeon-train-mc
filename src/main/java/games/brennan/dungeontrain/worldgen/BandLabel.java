package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
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
        return new BandLabel(bandAt(overworld, cycle, worldX), stageAt(cycle, worldX), cycle.cycleIndex(worldX));
    }

    /**
     * Just the {@link #band()} string at {@code worldX} — no stage or lap — for callers that scan many
     * columns ({@code /dtp next}). Empty when the world has no train.
     */
    public static String bandAt(ServerLevel overworld, int worldX) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return NONE.band;
        return bandAt(overworld, WorldGenCycle.fromConfig(), worldX);
    }

    private static String bandAt(ServerLevel overworld, WorldGenCycle cycle, int worldX) {
        TrainPhase phase = TrainPhase.phaseAt(overworld, worldX);
        return format(phase.displayName(), styleOf(overworld, cycle, phase, worldX));
    }

    /** The stage within the layout slot at {@code worldX}; empty on the classic single-period order. */
    private static String stageAt(WorldGenCycle cycle, int worldX) {
        int slot = cycle.slotIndexAt(worldX);
        if (slot < 0) return "";
        BandStages.Position at = BandStages.locate(stagesOf(cycle, slot), cycle.slotLocal(worldX));
        return at == null ? "" : at.describe();
    }

    /** The stages of layout slot {@code slot} with the live config — the list the panel counts and {@code /dtp <band> <subsection>} targets. */
    public static java.util.List<BandStages.Stage> stagesOf(WorldGenCycle cycle, int slot) {
        int[] mults = cycle.stageMultipliers();
        return BandStages.of(cycle.layout(), slot,
            mults == null ? 1 : mults.length, cycle.stageBlocks(), cycle.beachBlocks(),
            SpheresProgressionConfig.segments(), SpheresProgressionConfig.exitTaperBlocks(),
            SpheresProgressionConfig.exitVoidBlocks());
    }

    /** The styled occurrence at {@code worldX} within {@code phase}, or empty when it is the plain look. */
    private static String styleOf(ServerLevel overworld, WorldGenCycle cycle, TrainPhase phase, int worldX) {
        // Superflat has no phase of its own — name it so the panel and /dtp next see it as its own band.
        if (LegacyBands.isInBand(overworld, LegacyBandKind.SUPERFLAT, worldX)) return "Superflat";
        return switch (phase) {
            case NETHER -> !NetherBand.isInNetherBand(overworld, worldX) ? "" : switch (cycle.netherLookAt(worldX)) {
                case BETTER -> "Better Nether";
                case BOP -> "Biomes O' Plenty Nether";
                default -> "";
            };
            case END -> switch (cycle.endLookAt(worldX)) {
                case BETTER -> "Better End";
                case BOP -> "Biomes O' Plenty End";
                default -> "";
            };
            case OVERWORLD -> switch (SecondLapOverworld.at(cycle, worldX)) {
                case WWOO -> "WWOO";
                case BOP -> "Biomes O' Plenty";
                case VANILLA -> UpsideDownBand.isInExitFade(overworld, worldX) ? "Reassembly" : "";
            };
            case CHUNCKS -> cycle.isInMixZone(worldX) ? "Mix" : "";
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
