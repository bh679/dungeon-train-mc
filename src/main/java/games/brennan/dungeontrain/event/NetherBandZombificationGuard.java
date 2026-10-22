package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Makes piglins, piglin brutes and hoglins behave like they're in the real Nether while they
 * stand inside the Nether transition band's core ({@link NetherBand#isInNetherBiome}) — no
 * zombification, no trembling/shaking. The same holds in the other bands the "piglings" carriage
 * room rides through (Far Lands, Chuncks and its mix zone, Stacks, Spheres) and inside dimensional
 * (portal) rooms, which are stamped into the overworld's basement/attic — see {@link #isPiglinSafeAt}.
 *
 * <p>Every one of those places is still the <b>OVERWORLD</b> dimension. The band's core is netherrack/real-Nether terrain but still the <b>OVERWORLD</b>
 * dimension, and vanilla decides conversion purely by dimension: {@code AbstractPiglin
 * .isConverting()} is {@code true} whenever the dimension isn't piglin-safe and the mob isn't
 * immune. We flip the synced {@code DATA_IMMUNE_TO_ZOMBIFICATION} flag via the public
 * {@code setImmuneToZombification}, which both stops the server conversion timer <em>and</em>
 * stops the client shake — {@code PiglinRenderer}/{@code HoglinRenderer} gate the shake on
 * {@code isConverting()}, and the flag syncs to the client. No rendering mixin needed.</p>
 *
 * <p>Immunity is toggled by position so a mob reverts to normal overworld behaviour once it
 * leaves the core. A plain accounting tag (not a carriage-contents prefix, so kill-ahead /
 * confinement ignore it — same convention as {@link NetherMobSpawner}) records the immunity we
 * applied, so the synced flag is only written on band entry/exit and we never clear immunity
 * some other system set.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherBandZombificationGuard {

    /** Marks a mob whose zombification immunity WE set because it's in the nether core. */
    private static final String TAG = "dungeontrain_nether_no_zombify";

    /** Re-evaluate at most once per second per mob — far inside the ~300-tick conversion window. */
    private static final int CHECK_PERIOD_TICKS = 20;

    private NetherBandZombificationGuard() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof AbstractPiglin) && !(entity instanceof Hoglin)) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (entity.tickCount % CHECK_PERIOD_TICKS != 0) return;

        boolean safe = isPiglinSafeAt(level, entity.getX(), entity.getY(), entity.getZ());
        boolean ours = entity.getTags().contains(TAG);
        if (safe == ours) return; // steady state — nothing to do, no synced-data write

        setImmune(entity, safe);
        if (safe) {
            entity.addTag(TAG);
        } else {
            entity.removeTag(TAG);
        }
    }

    /**
     * True where piglins/hoglins should act as if in the Nether: the Nether band's core, the Far Lands,
     * Chuncks (mix zone included, as {@code TrainPhase#phaseAt} reads it), Stacks and Spheres band cores,
     * and any portal room copy or its corridors.
     */
    private static boolean isPiglinSafeAt(ServerLevel level, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (NetherBand.isInNetherBiome(level, bx, bz)) return true;
        if (LegacyBands.isInBand(level, LegacyBandKind.FAR_LANDS, bx)) return true;
        if (ChuncksBand.isInBand(level, bx)) return true;
        if (StacksBand.isInBand(level, bx)) return true;
        if (SpheresBand.isInBand(level, bx, bz)) return true;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (data.startsWithTrain() && WorldGenCycle.fromConfig().isInMixZone(bx)) return true;
        return PortalCarriageEvents.isInsidePortalStructure(data.dims(), x, y, z);
    }

    private static void setImmune(Entity entity, boolean immune) {
        if (entity instanceof AbstractPiglin piglin) {
            piglin.setImmuneToZombification(immune);
        } else if (entity instanceof Hoglin hoglin) {
            hoglin.setImmuneToZombification(immune);
        }
    }
}
