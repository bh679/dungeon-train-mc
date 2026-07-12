package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bundled {@code block id -> loot prefab id} registry, read from
 * {@code /data/dungeontrain/containers/block_loot_defaults.json}. Lets a block
 * type (e.g. {@code minecraft:chiseled_bookshelf}) fall back to a named
 * {@link LootPrefabStore} prefab whenever a container of that type has no
 * curated loot of its own — no per-position link/pool and no variant-sidecar
 * entry — instead of staying empty forever.
 *
 * <p>The fallback is probabilistic ({@link #resolveDefaultPool}), not
 * guaranteed: it's meant for "some fraction of unlabelled X look like they
 * have Y in them," not "every X always has Y."</p>
 */
public final class BlockLootDefaults {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "/data/dungeontrain/containers/block_loot_defaults.json";

    /** Chance a covered block type rolls its default prefab: 1-in-5. */
    private static final int DEFAULT_CHANCE_PCT = 20;
    private static final long SALT_DEFAULT_BLOCK_LOOT = 0xB10CDEFA07DEFA17L;

    private static final Map<ResourceLocation, String> DEFAULTS = new HashMap<>();

    private BlockLootDefaults() {}

    public static synchronized void reload() {
        DEFAULTS.clear();
        try (InputStream in = BlockLootDefaults.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.info("[DungeonTrain] No block loot defaults registry at {}", RESOURCE);
                return;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                if (!root.isJsonObject()) return;
                JsonObject obj = root.getAsJsonObject();
                for (String key : obj.keySet()) {
                    JsonElement value = obj.get(key);
                    if (!value.isJsonPrimitive()) continue;
                    ResourceLocation block = ResourceLocation.tryParse(key);
                    if (block == null) {
                        LOGGER.warn("[DungeonTrain] Ignoring invalid block id '{}' in block loot defaults", key);
                        continue;
                    }
                    DEFAULTS.put(block, value.getAsString());
                }
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read block loot defaults registry: {}", e.toString());
        }
        LOGGER.info("[DungeonTrain] Block loot defaults registry loaded — {} entries", DEFAULTS.size());
    }

    public static synchronized void clear() {
        DEFAULTS.clear();
    }

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        reload();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clear();
    }

    /**
     * Resolve the default loot pool for a container of {@code state}'s block
     * type at {@code localPos}, if the block type is registered AND a seeded
     * 1-in-5 roll hits. Deterministic per {@code (localPos, worldSeed,
     * carriageIndex)} — same seeded-chance idiom as every other roll in
     * {@link ContainerContentsRoller}, so a given cell at a given world seed
     * always makes the same call.
     */
    public static synchronized Optional<ContainerContentsPool> resolveDefaultPool(
            BlockState state, BlockPos localPos, long worldSeed, int carriageIndex) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String prefabId = DEFAULTS.get(blockId);
        if (prefabId == null) return Optional.empty();
        if (!ContainerContentsRoller.rollChance(DEFAULT_CHANCE_PCT, localPos, worldSeed,
                carriageIndex, /*slot*/ 0, SALT_DEFAULT_BLOCK_LOOT)) {
            return Optional.empty();
        }
        return LootPrefabStore.load(prefabId).map(LootPrefabStore.Data::pool);
    }
}
