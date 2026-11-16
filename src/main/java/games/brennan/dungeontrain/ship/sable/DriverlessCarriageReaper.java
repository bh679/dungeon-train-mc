package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Deletes carriage groups that come back from Sable holding with no train to belong to.
 *
 * <p><b>Why this exists.</b> Trains do not persist: every server start builds a new one, and the
 * shutdown sweep deletes the loaded groups. A group culled to holding is not loaded, so it stays on
 * disk, while its driver lives only in memory. Sable wakes it as soon as the chunks under it load
 * (at server start for any group near spawn), and with no driver it is an ordinary physics body:
 * measured 2026-11-05, groups tumbled off the line into the sea, others were carried along on top of
 * the new train, and a player could stand on them. Culled again, they wake again, for good.</p>
 *
 * <p><b>Why on wake.</b> It also covers a crash, which runs no shutdown code, and saves that already
 * hold such groups. Sable can only delete a live sub-level for good ({@code delete} on a held one
 * is a no-op): {@code removeSubLevel(REMOVED)} then {@code queueDeletion} drops its holding pointer,
 * so it never comes back. Entities are kicked out by that path rather than deleted.</p>
 *
 * <p><b>Not in the load call.</b> {@code loadHoldingSubLevel} runs in the middle of Sable's own
 * holding pass, so {@link #noteWoken} only queues and the next level tick decides. Groups woken while
 * the world loads, before any tick, are handled ahead of the bootstrap train so it never builds into
 * them.</p>
 *
 * <p>Server thread only. Bytecode-verified against {@code sable-2.0.5+mc1.21.1}; re-verify the
 * funnel in {@code SubLevelHoldingChunkMapLoadMixin} on any {@code sable_version} bump.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriverlessCarriageReaper {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceKey<Level>, Set<UUID>> WOKEN = new HashMap<>();
    private static final AtomicInteger REAPED_COUNT = new AtomicInteger();

    private DriverlessCarriageReaper() {}

    /** Sable took {@code subLevelId} out of holding in {@code level}, by any path. */
    public static void noteWoken(ServerLevel level, UUID subLevelId) {
        WOKEN.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(subLevelId);
    }

    /** Groups deleted since the JVM started. For the debug readout. */
    public static int reapedCount() {
        return REAPED_COUNT.get();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) drain(level);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) drain(level);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WOKEN.clear();
    }

    private static void drain(ServerLevel level) {
        Set<UUID> woken = WOKEN.remove(level.dimension());
        if (woken == null) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (UUID id : woken) {
            // Gone already (a failed load, or culled again): if it wakes again it is queued again.
            SubLevel sub = container.getSubLevel(id);
            if (sub instanceof ServerSubLevel subLevel && !subLevel.isRemoved()) judgeAndReap(level, subLevel);
        }
    }

    private static void judgeAndReap(ServerLevel level, ServerSubLevel subLevel) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level.getServer().overworld());
        CarriageDims dims = data.dims();
        BoundingBox3ic plot = subLevel.getPlot().getBoundingBox();
        DriverlessCarriageRule.Verdict verdict = DriverlessCarriageRule.judge(
            SableManagedShip.driverFor(subLevel) != null,
            DriverlessCarriageRule.isMarked(subLevel.getUserDataTag()),
            data.startsWithTrain(),
            plot.maxX() - plot.minX() + 1, plot.maxY() - plot.minY() + 1, plot.maxZ() - plot.minZ() + 1,
            dims.length(), dims.width(), dims.height(),
            data.getGenerationConfig().groupSize(), CarriagePlacer.halfPadLen(dims));
        if (verdict == DriverlessCarriageRule.Verdict.KEEP) return;
        Vector3dc pos = subLevel.logicalPose().position();
        LOGGER.info("[DungeonTrain] Deleting leftover carriage group {} at ({}, {}, {}) in {}: {}",
            subLevel.getUniqueId(), Math.round(pos.x()), Math.round(pos.y()), Math.round(pos.z()),
            level.dimension().location(), verdict);
        Shipyards.of(level).delete(new SableManagedShip(subLevel));
        REAPED_COUNT.incrementAndGet();
    }
}
