package games.brennan.dungeontrain.worldgen;

/**
 * Runtime kill-switch for the worldgen band <b>early-outs</b> (the off-band skip paths in
 * {@code NetherBandTerrainDensityFunction}, {@code BandBiomeDecision} and {@code TrackBedFeature}).
 * {@code /dungeontrain debug band-earlyout on|off|status} flips it live, driving the Gate 2
 * matched-toggle A/B: ride the same seed with the optimisation on vs off and compare
 * {@code [gen.timing]} — the outputs are byte-identical by construction (pinned by the seam-safety
 * tests), so ONLY the timings should differ. Same pattern as {@code PhysicsFreezeController.ENABLED}.
 *
 * <p>Default ON (the optimisation is the shipping behaviour). {@code volatile} so worldgen worker
 * threads observe a toggle immediately; per-chunk staleness during the flip is harmless because both
 * paths produce identical blocks.</p>
 */
public final class BandEarlyOuts {

    /** Live gate read by the three early-out sites. */
    public static volatile boolean ENABLED = true;

    private BandEarlyOuts() {}
}
