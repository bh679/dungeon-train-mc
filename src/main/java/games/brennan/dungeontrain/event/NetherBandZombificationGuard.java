package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.NetherBand;
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
 * zombification, no trembling/shaking.
 *
 * <p>The band's core is netherrack/real-Nether terrain but still the <b>OVERWORLD</b>
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
public final class NetherBandZombificationGuard {

    /** Marks a mob whose zombification immunity WE set because it's in the nether core. */
    private static final String TAG = "dungeontrain_nether_no_zombify";

    /** Re-evaluate at most once per second per mob — far inside the ~300-tick conversion window. */
    private static final int CHECK_PERIOD_TICKS = 20;

    private NetherBandZombificationGuard() {}

        public static void onEntityTick(net.minecraft.world.entity.Entity tickedEntity) {
        Entity entity = tickedEntity;
        if (!(entity instanceof AbstractPiglin) && !(entity instanceof Hoglin)) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (entity.tickCount % CHECK_PERIOD_TICKS != 0) return;

        boolean inNether = NetherBand.isInNetherBiome(level, (int) Math.floor(entity.getX()));
        boolean ours = entity.getTags().contains(TAG);
        if (inNether == ours) return; // steady state — nothing to do, no synced-data write

        setImmune(entity, inNether);
        if (inNether) {
            entity.addTag(TAG);
        } else {
            entity.removeTag(TAG);
        }
    }

    private static void setImmune(Entity entity, boolean immune) {
        if (entity instanceof AbstractPiglin piglin) {
            piglin.setImmuneToZombification(immune);
        } else if (entity instanceof Hoglin hoglin) {
            hoglin.setImmuneToZombification(immune);
        }
    }
}
