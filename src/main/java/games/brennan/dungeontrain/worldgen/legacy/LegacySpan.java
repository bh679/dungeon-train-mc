package games.brennan.dungeontrain.worldgen.legacy;

/**
 * One legacy band's slot in the {@link games.brennan.dungeontrain.worldgen.WorldGenCycle}:
 * {@code [leadGap] [fade] [hold] [fade]} — a plain-overworld lead-in, an entry fade where chunks switch
 * to the old generator one by one, the full-strength core, and a mirrored exit fade back to modern terrain.
 *
 * <p>A {@code hold <= 0} span is disabled and contributes nothing to the cycle period (its gap and fades
 * collapse too), so an unbuilt or switched-off era never shifts the layout.</p>
 *
 * @param kind    which old generator fills the band
 * @param leadGap plain-overworld blocks before the entry fade
 * @param fade    length of EACH fade (entry and exit); 0 = hard chunk wall
 * @param hold    length of the full-strength core; 0 disables the span
 */
public record LegacySpan(LegacyBandKind kind, int leadGap, int fade, int hold) {

    /** Core length; 0 when disabled. */
    public long holdLen() {
        return Math.max(0, hold);
    }

    /** One fade's length; 0 when the span is disabled. */
    public long fadeLen() {
        return holdLen() > 0L ? Math.max(0, fade) : 0L;
    }

    /** Lead-in gap length; 0 when the span is disabled. */
    public long leadGapLen() {
        return holdLen() > 0L ? Math.max(0, leadGap) : 0L;
    }

    /** Whole slot: {@code leadGap + fade + hold + fade}; 0 when disabled. */
    public long totalLen() {
        return leadGapLen() + 2L * fadeLen() + holdLen();
    }
}
