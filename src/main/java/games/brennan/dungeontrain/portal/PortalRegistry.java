package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world registry of dimensional-portal pairs. Persisted on the overworld
 * (the conventional "shared" level for cross-dim metadata in Minecraft mods)
 * at {@code <world>/data/dungeontrain_portal_registry.dat}.
 *
 * <p>Index strategy: the primary store is keyed by pair {@link UUID}; a
 * secondary {@code byPosition} index maps {@link PortalEndpoint} → pair id
 * so {@link #findPartner} can resolve "given a portal at this dim+pos,
 * where's its partner?" in O(1) without scanning all pairs.</p>
 *
 * <p>Why overworld-anchored: dimension-level {@code DataStorage} is per-dim,
 * but the registry spans dimensions by design (each pair has endpoints in
 * two dims). Anchoring on the overworld matches how vanilla and other mods
 * persist cross-dim data (e.g. nether portal links, end portal coords).</p>
 *
 * <p>Concurrency: portal creation happens during world-gen feature placement
 * (parallel worker threads). All mutating methods are {@code synchronized}
 * so the pair set and the secondary index stay consistent.</p>
 */
public final class PortalRegistry extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String NAME = "dungeontrain_portal_registry";
    private static final String TAG_PAIRS = "pairs";

    private final Map<UUID, PortalPair> pairs = new HashMap<>();
    private final Map<PortalEndpoint, UUID> byPosition = new HashMap<>();

    private PortalRegistry() {}

    /**
     * Resolves the registry on the server's overworld, creating it if no
     * prior instance has been saved. Safe to call from any thread (vanilla
     * {@code DataStorage.computeIfAbsent} synchronises internally).
     */
    public static PortalRegistry get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                PortalRegistry::new,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    /**
     * Register a new portal pair. Both endpoints are indexed; the returned
     * UUID is also stored on the pair for round-trip identity. Setting
     * {@code dirty} so the new pair is persisted at the next save.
     *
     * @return the new pair's id
     */
    public synchronized UUID addPair(ResourceKey<Level> dimA, BlockPos posA,
                                     ResourceKey<Level> dimB, BlockPos posB) {
        PortalEndpoint a = new PortalEndpoint(dimA, posA.immutable());
        PortalEndpoint b = new PortalEndpoint(dimB, posB.immutable());
        UUID id = UUID.randomUUID();
        PortalPair pair = new PortalPair(id, a, b);
        pairs.put(id, pair);
        byPosition.put(a, id);
        byPosition.put(b, id);
        setDirty();
        LOGGER.debug("[Portal] Registered pair {}: {} ↔ {}", id, a, b);
        return id;
    }

    /**
     * Looks up the partner of the endpoint at {@code (dim, pos)}. Returns
     * {@code null} if no portal is registered at that position, or if the
     * pair is corrupt (only one endpoint indexed).
     */
    @Nullable
    public synchronized PortalEndpoint findPartner(ResourceKey<Level> dim, BlockPos pos) {
        PortalEndpoint key = new PortalEndpoint(dim, pos.immutable());
        UUID id = byPosition.get(key);
        if (id == null) return null;
        PortalPair pair = pairs.get(id);
        if (pair == null) {
            // Inconsistent index — clean up.
            byPosition.remove(key);
            setDirty();
            return null;
        }
        return pair.a().equals(key) ? pair.b() : pair.a();
    }

    /** Looks up the {@link PortalPair} containing the given endpoint, or {@code null}. */
    @Nullable
    public synchronized PortalPair findPair(ResourceKey<Level> dim, BlockPos pos) {
        UUID id = byPosition.get(new PortalEndpoint(dim, pos.immutable()));
        return id == null ? null : pairs.get(id);
    }

    /** Returns an unmodifiable snapshot of all pairs at call time. */
    public synchronized Collection<PortalPair> all() {
        return Collections.unmodifiableCollection(new HashMap<>(pairs).values());
    }

    /** Removes a pair by id. Cleans both index entries. No-op if unknown. */
    public synchronized void removePair(UUID id) {
        PortalPair pair = pairs.remove(id);
        if (pair == null) return;
        byPosition.remove(pair.a());
        byPosition.remove(pair.b());
        setDirty();
        LOGGER.debug("[Portal] Removed pair {}", id);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PortalPair pair : pairs.values()) {
            list.add(pair.toNbt());
        }
        tag.put(TAG_PAIRS, list);
        return tag;
    }

    /**
     * Factory loader. Drops malformed pairs silently — a single corrupt
     * entry should not prevent the rest of the registry from loading.
     */
    private static PortalRegistry load(CompoundTag tag) {
        PortalRegistry reg = new PortalRegistry();
        ListTag list = tag.getList(TAG_PAIRS, Tag.TAG_COMPOUND);
        int dropped = 0;
        for (int i = 0; i < list.size(); i++) {
            PortalPair pair = PortalPair.fromNbt(list.getCompound(i));
            if (pair == null) {
                dropped++;
                continue;
            }
            reg.pairs.put(pair.id(), pair);
            reg.byPosition.put(pair.a(), pair.id());
            reg.byPosition.put(pair.b(), pair.id());
        }
        if (dropped > 0) {
            LOGGER.warn("[Portal] Dropped {} malformed pair entries during registry load", dropped);
        }
        LOGGER.info("[Portal] Loaded registry with {} pairs", reg.pairs.size());
        return reg;
    }
}
