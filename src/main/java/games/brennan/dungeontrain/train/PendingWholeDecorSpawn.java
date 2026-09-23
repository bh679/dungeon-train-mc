package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A whole room's or whole group's captured entities, waiting for the group to settle before they are
 * spawned. The third of the deferred entity records, beside {@link PendingContentsEntitySpawn} ("generate
 * this carriage's contents") and {@link PendingRelayEntitySpawn} ("put back what the world that built
 * this had standing in it"). This one says: put back what the template carries.
 *
 * <p>Deferred for the reason both siblings are. A whole slot's blocks are stamped in the world only to be
 * lifted into the Sable sub-level the same tick, so an entity spawned at stamp time is left standing on
 * the track when the train pulls away; and an entity added while the placement-collision tracker is still
 * shifting the group is the displacement bug the contents pass was split in two to avoid. All three wait
 * for the same settle signal in {@code TrainCarriageAppender}.</p>
 *
 * <p>Its own record rather than a {@link PendingContentsEntitySpawn} with a flag: that array is what
 * {@code PlayerMobGroupSpawner} picks a host carriage from, and a whole room is somebody's authored room
 * rather than a stage for this world's echoes — the same reasoning that keeps the relay record separate.</p>
 *
 * <p>The template is carried whole rather than its entity list alone, because
 * {@link games.brennan.dungeontrain.template.TemplateDecor#spawn} reads the list off the template and
 * needs its size to clip each carriage's slice.</p>
 *
 * @param shipyardOrigin    the run's lowest corner in shipyard coords — the frame the template's entity
 *                          offsets are relative to, and the origin its blocks were stamped at
 * @param template          the stamped template, whose {@code entities} list is what gets spawned
 * @param firstCarriagePIdx the pIdx of the carriage at {@code shipyardOrigin}; later carriages in a group
 *                          follow from it, and each entity is tagged to the one it stands in
 * @param carriages         how many carriages the template spans — 1 for a room, the group size for a group
 */
public record PendingWholeDecorSpawn(
    BlockPos shipyardOrigin,
    StructureTemplate template,
    int firstCarriagePIdx,
    int carriages
) {
}
