package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Holds a builder world's entities where the author put them, while the floor under them is a ghost.
 *
 * <p>Ghosting the carriage deck ({@code BuilderGhostCells}) takes the blocks away for real — that is
 * what makes the rest of the train visible from inside one. Everything standing on that deck
 * promptly discovered there was nothing under it: armour stands, item frames, paintings and mobs an
 * author had placed as contents fell out of the carriage and landed on the platform, or kept going.
 * A decoration that will not stay where it was put is not a decoration.</p>
 *
 * <p>So while the deck is lifted, entities inside the build are anchored — no gravity, no motion.
 * Not frozen in any deeper sense: they can still be pushed around by the author, picked up, broken
 * and replaced. The anchor is released the moment the world goes to
 * {@link BuilderGhostMode#SOLID}, because then there is a real floor again and holding them up would
 * be the odd behaviour.</p>
 *
 * <p>Players are never touched. A builder world is creative and flying, and taking gravity off
 * somebody who is walking around would be felt immediately and read as a bug.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuilderEntityAnchor {

    /**
     * Ticks between passes.
     *
     * <p>A pass rather than a one-shot at lift time, because entities arrive after it: an author
     * places an armour stand at any moment, and a contents stamp spawns a roomful at once. Half a
     * second is quick enough that nothing falls far enough to notice.</p>
     */
    private static final int INTERVAL_TICKS = 10;

    /** How far past the build to look, so something on the lip of the deck is caught too. */
    private static final double MARGIN = 1.0;

    private static int tickCounter = 0;

    private BuilderEntityAnchor() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return;
        }
        if (++tickCounter < INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        boolean anchored = data.builderGhostMode().lifts();
        CarriageDims dims = data.dims();
        for (BoundingBox box : BuilderBounds.volumesFor(level)) {
            apply(level, box, dims, anchored);
        }
    }

    /**
     * Anchor (or release) everything standing in one build volume.
     *
     * <p>Only entities whose gravity flag disagrees with what it should be are written to, so a
     * settled world does nothing at all here — and, more importantly, an author who has deliberately
     * set an armour stand's own no-gravity flag while the deck is solid keeps it: this only ever
     * releases what it is holding, which is what it set.</p>
     */
    private static void apply(ServerLevel level, BoundingBox box, CarriageDims dims, boolean anchor) {
        AABB area = new AABB(box.minX() - MARGIN, box.minY() - MARGIN, box.minZ() - MARGIN,
                box.maxX() + 1 + MARGIN, box.maxY() + 1 + MARGIN, box.maxZ() + 1 + MARGIN);
        List<Entity> entities = level.getEntities((Entity) null, area,
                e -> !(e instanceof Player));

        for (Entity entity : entities) {
            if (anchor) {
                if (!entity.isNoGravity()) {
                    entity.setNoGravity(true);
                    anchoredHere(entity);
                }
                // Zero the fall it had already started before the pass reached it, so it settles
                // back at the height it was placed rather than wherever it got to.
                if (entity.getDeltaMovement().lengthSqr() > 1.0E-6) {
                    entity.setDeltaMovement(Vec3.ZERO);
                    entity.hasImpulse = true;
                }
            } else if (wasAnchoredHere(entity)) {
                entity.setNoGravity(false);
                entity.getPersistentData().remove(ANCHOR_TAG);
            }
        }
    }

    /**
     * Marks an entity whose no-gravity flag this class turned on.
     *
     * <p>Without it, going to Solid would drop gravity back onto every anchored entity including the
     * ones an author had deliberately made float — this class would be handing back a setting it
     * never took.</p>
     */
    private static final String ANCHOR_TAG = "DungeonTrainBuilderAnchored";

    private static void anchoredHere(Entity entity) {
        entity.getPersistentData().putBoolean(ANCHOR_TAG, true);
    }

    private static boolean wasAnchoredHere(Entity entity) {
        return entity.getPersistentData().getBoolean(ANCHOR_TAG);
    }
}
