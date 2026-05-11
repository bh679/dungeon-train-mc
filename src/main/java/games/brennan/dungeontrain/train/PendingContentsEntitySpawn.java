package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;

/**
 * Captures the inputs needed to spawn one carriage's contents entities at a
 * deferred time. Stashed on {@link TrainTransformProvider} immediately after
 * blocks are placed (via {@link CarriagePlacer#applyContentsBlocksAt}) and
 * consumed by the placement-collision tracker once the carriage's group has
 * settled in place.
 *
 * <p>Why a record: it carries the (origin, variant, dims, config, carriageIndex)
 * tuple that {@link CarriagePlacer#applyContentsEntitiesAt} needs as a single
 * immutable value. No mutation; rebuild a new record if anything has to change.</p>
 *
 * @param shipyardOrigin Carriage's lowest-corner position in shipyard coords
 *                       — the same {@code origin} the blocks pass used so
 *                       entities land at matching local offsets.
 * @param variant        Carriage shell variant; used to filter out FLATBED
 *                       (the entity pass is a no-op for flatbeds) and to
 *                       reproduce the deterministic contents pick.
 * @param dims           Carriage footprint, immutable per group's lifetime.
 * @param config         Generation config that drives the deterministic
 *                       {@code (seed, carriageIndex)} contents pick.
 * @param carriageIndex  The carriage's pIdx — same value used as both the
 *                       contents-pick index AND the diagnostic-tag suffix
 *                       ({@code dungeontrain_contents_pidx_<carriageIndex>}).
 */
public record PendingContentsEntitySpawn(
    BlockPos shipyardOrigin,
    CarriageVariant variant,
    CarriageDims dims,
    CarriageGenerationConfig config,
    int carriageIndex
) {
}
