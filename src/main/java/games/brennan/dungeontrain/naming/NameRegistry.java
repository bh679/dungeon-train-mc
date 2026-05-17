package games.brennan.dungeontrain.naming;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory registry of every bundled naming pool, chain, and selector
 * under {@code /data/dungeontrain/naming/}.
 *
 * <p>Loaded once at {@link ServerStartingEvent}, mirrors
 * {@code RandomBookRegistry} — server-thread synchronous, no
 * datapack-reload listener (the naming corpus is shipped-only).</p>
 *
 * <p>Feeds {@link NameComposer} which is invoked from
 * {@code ContainerContentsRoller.rollItemStack} after the enchantment
 * gate — naturally-spawned swords get a {@code CUSTOM_NAME} component
 * stamped at roll time using the same deterministic seed mix as
 * durability/enchantment.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NameRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String JSON_EXT = ".json";

    private static final String POOLS_PREFIX     = "/data/" + DungeonTrain.MOD_ID + "/naming/pools/";
    private static final String CHAINS_PREFIX    = "/data/" + DungeonTrain.MOD_ID + "/naming/chains/";
    private static final String SELECTORS_PREFIX = "/data/" + DungeonTrain.MOD_ID + "/naming/selectors/";

    private static final Map<ResourceLocation, NamePool> POOLS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> CHAINS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameSelector> SELECTORS = new LinkedHashMap<>();

    private NameRegistry() {}

    public static synchronized void reload() {
        POOLS.clear();
        CHAINS.clear();
        SELECTORS.clear();

        int pools     = loadEach(POOLS_PREFIX,     "naming/pools/",     NameKind.POOL);
        int chains    = loadEach(CHAINS_PREFIX,    "naming/chains/",    NameKind.CHAIN);
        int selectors = loadEach(SELECTORS_PREFIX, "naming/selectors/", NameKind.SELECTOR);

        LOGGER.info("[DungeonTrain] Naming registry loaded — {} pools, {} chains, {} selectors",
            pools, chains, selectors);
    }

    private static int loadEach(String resourcePrefix, String idPathPrefix, NameKind kind) {
        Set<String> basenames = BundledNbtScanner.scanBasenames(
            NameRegistry.class, resourcePrefix, LOGGER, JSON_EXT);
        int loaded = 0;
        for (String basename : basenames) {
            String resourcePath = resourcePrefix + basename + JSON_EXT;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                DungeonTrain.MOD_ID, idPathPrefix + basename);
            try (InputStream in = NameRegistry.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warn("[DungeonTrain] Naming: scanner found '{}' but resource stream is null — skipping",
                        basename);
                    continue;
                }
                switch (kind) {
                    case POOL -> {
                        NamePool pool = NameCodec.parsePool(in, id);
                        POOLS.put(pool.id(), pool);
                    }
                    case CHAIN -> {
                        NameChain chain = NameCodec.parseChain(in, id);
                        CHAINS.put(chain.id(), chain);
                    }
                    case SELECTOR -> {
                        NameSelector sel = NameCodec.parseSelector(in, id);
                        SELECTORS.put(sel.id(), sel);
                    }
                }
                loaded++;
            } catch (NameCodec.NameParseException e) {
                LOGGER.error("[DungeonTrain] Naming: failed to parse {} — {}", resourcePath, e.getMessage());
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] Naming: unexpected error reading {} — {}", resourcePath, e.toString());
            }
        }
        return loaded;
    }

    public static synchronized Optional<NamePool> pool(ResourceLocation id) {
        return Optional.ofNullable(POOLS.get(id));
    }

    public static synchronized Optional<NameChain> chain(ResourceLocation id) {
        return Optional.ofNullable(CHAINS.get(id));
    }

    /**
     * First selector whose {@code applies_to} tag matches the supplied tag
     * id, or empty when no selector covers this item kind. Iteration order
     * is the registry's insertion order (filesystem-sorted by
     * {@link BundledNbtScanner}) so the result is deterministic.
     */
    public static synchronized Optional<NameSelector> selectorFor(ResourceLocation tagId) {
        for (NameSelector sel : SELECTORS.values()) {
            if (sel.appliesTo().equals(tagId)) return Optional.of(sel);
        }
        return Optional.empty();
    }

    /**
     * First selector whose {@code applies_to} tag is on {@code stack}, or
     * empty when no registered selector covers the stack's item type.
     * Iteration order is filesystem-sorted (deterministic). When several
     * selectors could match a single item (e.g. a future selector for the
     * vanilla umbrella {@code minecraft:enchantable/weapon} tag alongside
     * a narrower {@code minecraft:swords} selector), the first declared
     * wins — control the order by file name.
     */
    public static synchronized Optional<NameSelector> findMatching(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        for (NameSelector sel : SELECTORS.values()) {
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, sel.appliesTo());
            if (stack.is(tagKey)) return Optional.of(sel);
        }
        return Optional.empty();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    private enum NameKind { POOL, CHAIN, SELECTOR }
}
