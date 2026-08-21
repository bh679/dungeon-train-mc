package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;

/**
 * A leased carriage's captured entities, waiting for the group to settle before they are spawned. The
 * relay twin of {@link PendingContentsEntitySpawn}: that one says "generate this carriage's contents",
 * this one says "put back exactly what the world that built this had standing in it".
 *
 * <p>Why deferred rather than spawned by {@link CarriageBlockSnapshot#place}: an entity added while the
 * placement-collision tracker is still shifting the group is the displacement bug the contents pass was
 * split in two to avoid. The blocks travel with the ship regardless of a shift; entities do not, so both
 * kinds of entity spawn wait for the same settle signal in
 * {@code TrainCarriageAppender.runPlacementCollisionTracker}.</p>
 *
 * <p>Kept as its own record and its own provider slot rather than folded into
 * {@link PendingContentsEntitySpawn}: that array is also what {@code PlayerMobGroupSpawner} picks a host
 * carriage from, and a relay carriage is not a candidate for that — a leased build is someone else's
 * room, not a stage for this world's echoes.</p>
 *
 * @param shipyardOrigin the carriage's lowest corner in shipyard coords — the frame the entity offsets
 *                       in {@code ents} are relative to, and the same origin the blocks were stamped at
 * @param ents           the blob's {@code ents} list, exactly as {@link CarriageEntitySnapshot} wrote it
 * @param carriagePIdx   the carriage's pIdx, used for the contents tag + spawn anchor
 */
public record PendingRelayEntitySpawn(
    BlockPos shipyardOrigin,
    ListTag ents,
    int carriagePIdx
) {
}
