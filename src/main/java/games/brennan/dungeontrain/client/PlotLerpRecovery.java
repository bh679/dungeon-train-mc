package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Re-attaches client mobs whose carriage briefly vanished during a hold/reload.
 *
 * <p>{@code LivingEntityPlotLerpGuardMixin} cancels the vanilla lerp toward plot coordinates and
 * parks the target here. Each client tick, a parked mob whose carriage is back (a sub-level
 * contains the target again) gets its plot position set and is placed on the carriage, after which
 * Sable's own follow carries it. A mob Sable picked up by itself, or one removed, is dropped; one
 * whose carriage never returns is let go after {@link #HOLD_TICKS} and simply stays where it was
 * last drawn until the server next syncs it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PlotLerpRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(DtLogging.JITTER);

    /** How long a parked mob waits for its carriage: two seconds, well past a reload's gap. */
    static final int HOLD_TICKS = 40;

    enum Step { ATTACH, DROP, WAIT }

    private record Pending(Vec3 plotTarget, int ticksLeft) {}

    private static final Map<LivingEntity, Pending> PENDING = new WeakHashMap<>();

    private PlotLerpRecovery() {}

    /** Parks a mob's plot-space lerp target; a newer target replaces an older one. */
    public static void hold(LivingEntity entity, Vec3 plotTarget) {
        PENDING.put(entity, new Pending(plotTarget, HOLD_TICKS));
    }

    /** What to do with one parked mob this tick. */
    static Step step(boolean removedOrFollowed, boolean carriageFound, int ticksLeft) {
        if (removedOrFollowed) return Step.DROP;
        if (carriageFound) return Step.ATTACH;
        return ticksLeft <= 1 ? Step.DROP : Step.WAIT;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        Iterator<Map.Entry<LivingEntity, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Pending> entry = it.next();
            LivingEntity entity = entry.getKey();
            Pending pending = entry.getValue();
            boolean gone = entity.isRemoved() || entity.level() != mc.level
                || ((EntityStickExtension) entity).sable$getPlotPosition() != null;
            SubLevel carriage = gone ? null : Sable.HELPER.getContaining(mc.level, pending.plotTarget());
            switch (step(gone, carriage != null, pending.ticksLeft())) {
                case ATTACH -> {
                    ((EntityStickExtension) entity).sable$setPlotPosition(pending.plotTarget());
                    entity.setPos(carriage.logicalPose().transformPosition(pending.plotTarget()));
                    LOGGER.info("[DungeonTrain] Re-attached {} to its reloaded carriage after {} ticks",
                        entity.getType().toShortString(), HOLD_TICKS - pending.ticksLeft());
                    it.remove();
                }
                case DROP -> {
                    if (!gone) {
                        LOGGER.info("[DungeonTrain] {} lost its carriage for {} ticks; left in place",
                            entity.getType().toShortString(), HOLD_TICKS);
                    }
                    it.remove();
                }
                case WAIT -> entry.setValue(new Pending(pending.plotTarget(), pending.ticksLeft() - 1));
            }
        }
    }
}
