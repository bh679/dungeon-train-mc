package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where every live prefab anchor stands, per level, and a generation counter that moves whenever
 * anything a prefab ghost depends on changes — an anchor placed, removed or rebound, a prefab saved
 * or deleted.
 *
 * <p>Fed by {@code PrefabAnchorBlockEntity}'s load / remove hooks rather than by scanning plots: the
 * editor tick asks "did anything change?" every tick for every player, and a block walk of every
 * plot on that path is exactly the cost {@code EditorTypeMenus} had to be rescued from. With the
 * index, a steady editor costs one long compare per tick.</p>
 *
 * <p>Anchors on the spawn path register for the instant between their stamp and
 * {@code PrefabResolver} consuming them; nothing reads the index outside the editor tick, so that
 * churn is a few set operations and no more.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PrefabAnchorIndex {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> ANCHORS = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();

    /** Players who turned the prefab ghosts off. Same shape as {@link EditorDoorGhosts}' toggle. */
    private static final Set<UUID> DISABLED = ConcurrentHashMap.newKeySet();

    private PrefabAnchorIndex() {}

    public static void add(ResourceKey<Level> level, BlockPos pos) {
        if (ANCHORS.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(pos.immutable())) bump();
    }

    public static void remove(ResourceKey<Level> level, BlockPos pos) {
        Set<BlockPos> set = ANCHORS.get(level);
        if (set != null && set.remove(pos)) bump();
    }

    /** Snapshot of the anchors standing in {@code level}. */
    public static List<BlockPos> anchorsIn(ResourceKey<Level> level) {
        Set<BlockPos> set = ANCHORS.get(level);
        return set == null ? Collections.emptyList() : List.copyOf(set);
    }

    /** Something a ghost depends on changed — a binding, a saved design. */
    public static void bump() {
        GENERATION.incrementAndGet();
    }

    public static long generation() {
        return GENERATION.get();
    }

    public static void setEnabled(UUID playerId, boolean on) {
        if (on) DISABLED.remove(playerId);
        else DISABLED.add(playerId);
        bump();
    }

    public static boolean isEnabled(UUID playerId) {
        return !DISABLED.contains(playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ANCHORS.clear();
        DISABLED.clear();
        bump();
    }
}
