package games.brennan.dungeontrain.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime kill-switch and veto counter for the disintegration band's <b>fluid guard</b> — the
 * {@code FlowingFluidDisintegrationMixin} veto that stops water and lava spreading into the band's
 * bottomless columns. {@code /dungeontrain debug fluidvoid on|off|status} flips it live, driving the
 * Gate 2 matched-toggle A/B: ride the same seed through a fade zone with the guard on vs off and
 * compare {@code [mspt]}. Same pattern as {@link BandEarlyOuts#ENABLED}.
 *
 * <p>Unlike the worldgen early-outs, the two arms here are <b>not</b> equivalent: with the guard off
 * liquid really does cascade into the void, which is the behaviour being measured. Terrain is
 * identical either way — this only vetoes runtime fluid spread, never generation.</p>
 *
 * <p>The counter exists because the mod has no fluid-tick or scheduled-tick instrumentation at all,
 * so without it an A/B could only watch {@code avgTickMs} move and could not attribute the change to
 * fluid load. It is reported by the {@code [fluid]} line in {@code TrainTickEvents}' 40-tick flush,
 * beside {@code [mspt]}.</p>
 */
public final class VoidFluidGuard {

    /** Live gate read by the fluid-spread veto. Default ON (the shipping behaviour). */
    public static volatile boolean ENABLED = true;

    /** Vetoed spreads since the last {@link #drainVetoes()}; written from the server thread. */
    private static final AtomicLong VETOES = new AtomicLong();

    private VoidFluidGuard() {}

    /** Record one vetoed fluid spread. */
    public static void countVeto() {
        VETOES.incrementAndGet();
    }

    /** Vetoes since the previous call, resetting the counter — used by the periodic log flush. */
    public static long drainVetoes() {
        return VETOES.getAndSet(0L);
    }
}
