package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.GraphicsCapabilities;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Per-photo context sampled on the client when a ride snapshot is captured — the facets the relay
 * Photos page filters by, alongside the shot's {@link SnapshotTag}: the biome the shot was taken in,
 * the worldgen band (Overworld / Nether / End / Upside-down), and the live difficulty level + cart
 * number at that moment. Immutable; captured once at {@link RideSnapshotCapture} grab time and carried
 * through to the death-screen gallery upload.
 *
 * <p>Every field is best-effort: {@code biome}/{@code band} are empty strings when unknown (e.g. a
 * non-train world or before the join-time band sync), {@code difficulty}/{@code cart} default to 0
 * before the first {@code BoardingProgressPacket}. The relay treats blanks as "untagged". {@code gfx}
 * is a compact graphics-stack tag (see {@link GraphicsCapabilities#facet()}), e.g. {@code dh+shaders+fabulous};
 * {@code shaderpack} is the active Iris/Oculus pack name (blank when none).</p>
 */
public record SnapshotMeta(String biome, String band, int difficulty, int cart, String gfx, String shaderpack) {

    public static final SnapshotMeta EMPTY = new SnapshotMeta("", "", 0, 0, "", "");

    /** The worldgen band matches the game's own music crossover: intensity ≥ this ⇒ inside the band. */
    private static final double BAND_THRESHOLD = 0.5;

    /**
     * Sample the four facets for the player's current position/progress. Never throws — any failure
     * yields {@link #EMPTY} so a capture is never lost over a missing biome or unsynced band.
     */
    public static SnapshotMeta sample(ClientLevel level, Player player) {
        try {
            String biome = "";
            String band = "OVERWORLD";
            if (level != null && player != null) {
                BlockPos pos = player.blockPosition();
                biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("");
                band = bandAt(player.getX());
            }
            return new SnapshotMeta(biome, band,
                    VersionHudOverlay.difficultyLevel(), VersionHudOverlay.travelledCarriageIndex(),
                    GraphicsCapabilities.facet(), GraphicsCapabilities.shaderPackName());
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * Classify the worldgen band from a world-X using the client-side band mirrors, in the same
     * precedence the server's {@code TrainPhase.phaseAt} uses: Upside-down, then the Nether core,
     * then the End/void core, else Overworld. Off-train worlds report OVERWORLD (every mirror
     * returns 0 / false when this world has no train).
     */
    private static String bandAt(double worldX) {
        if (ClientUpsideDownBand.isInBand((int) Math.floor(worldX))) return "UPSIDE_DOWN";
        if (ClientNetherBand.netherIntensityAt(worldX) >= BAND_THRESHOLD) return "NETHER";
        if (ClientVoidBand.endSkyIntensityAt(worldX) >= BAND_THRESHOLD) return "END";
        return "OVERWORLD";
    }
}
