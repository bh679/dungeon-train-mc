package games.brennan.dungeontrain.editor.surrounding;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * What the Train Builder currently has open, in the terms the surrounding-structure resolvers need:
 * where the build sits, how big it is, and which kind of thing it is.
 *
 * <p>This is the anchor the whole system hangs off. The Train Builder is not the legacy sky editor —
 * its builds sit on a platform at {@code BuilderWorldLayout.TRAIN_Y}, not on plots at {@code y=250} —
 * so the surrounding structures are resolved relative to the <em>parked train</em> rather than to an
 * {@code EditorCategory} plot.</p>
 *
 * <p>{@link BuilderWorldSetup#overlaySelection} stamps the picked template into <em>every</em> parked
 * carriage, so there is no single "opened index" to anchor on: {@link #origin()} is the first parked
 * carriage and {@link #trainLength()} spans all of them, which is what the structures around the
 * build (track beneath it, pillars under that) actually have to cover.</p>
 *
 * @param dims       the world's carriage dimensions — the size the build fills
 * @param carriages  how many carriages are parked, from {@link BuilderMode#carriageCount()}
 * @param mode       which builder mode is open
 * @param subTypeId  the open sub-type ({@code carriage_room}, {@code parts}, {@code portal_room}, …),
 *                   or empty when the mode has none
 * @param partKindId which part kind is being edited when {@code subTypeId} is {@code parts}, else empty
 * @param origin     world origin of the first parked carriage
 */
public record BuilderPlot(
        CarriageDims dims,
        int carriages,
        BuilderMode mode,
        String subTypeId,
        String partKindId,
        BlockPos origin
) {

    /** Total X span of the parked train — what the track and pillars beneath it have to cover. */
    public int trainLength() {
        return Math.max(1, carriages) * dims.length();
    }

    /**
     * Read the open build out of the world, or empty when this isn't a Train Builder world.
     *
     * <p>{@code builderMode} is null in every ordinary world, which is what makes this a cheap
     * per-tick gate — see {@code VariantOverlayRenderer.onLevelTick}.</p>
     */
    public static Optional<BuilderPlot> of(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        Optional<BuilderMode> modeOpt = BuilderMode.fromId(data.builderMode());
        if (modeOpt.isEmpty()) {
            return Optional.empty();
        }
        BuilderMode mode = modeOpt.get();
        CarriageDims dims = data.dims();
        // builderCarriages is -1 on worlds stamped before it was recorded; the mode's own count is
        // the same number the stamp used, so it's the correct fallback rather than a guess.
        int carriages = data.builderCarriages() > 0 ? data.builderCarriages() : mode.carriageCount();
        if (carriages <= 0) {
            // Track and dimension modes park no carriages — the build is the line itself, which
            // anchors at the world origin the track scene is laid around.
            return Optional.of(new BuilderPlot(dims, 0, mode,
                    orEmpty(data.builderSubType()), orEmpty(data.builderPartKind()), BlockPos.ZERO));
        }
        BlockPos origin = BuilderWorldSetup.carriageOrigin(dims, carriages, 0);
        return Optional.of(new BuilderPlot(dims, carriages, mode,
                orEmpty(data.builderSubType()), orEmpty(data.builderPartKind()), origin));
    }

    private static String orEmpty(String raw) {
        return raw == null ? "" : raw;
    }
}
