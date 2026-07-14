package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.internal.NameCodec;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies host-language localized AdventureItemNames (AIN) naming data on top of AIN's English
 * base, so generated item names + mob titles read in the world host's language — without a global
 * datapack override that would also hit English players.
 *
 * <p>AIN owns its naming registry ({@code data/&lt;ns&gt;/naming/**}, loaded by its own reload
 * listeners), so DT can't key it by locale through the datapack channel. Instead this reuses AIN's
 * public <b>session-overlay</b> API — the same mechanism its in-game editor uses to keep edits
 * across {@code /reload}: {@link NameRegistry#putPoolInMemory(NamePool, String)} /
 * {@link NameRegistry#putChainInMemory(NameChain)} push an entry into AIN's {@code POOL_OVERLAY}/
 * {@code CHAIN_OVERLAY}, which AIN re-applies on top of the freshly-loaded base on every reload.
 * So a locale overlay applied once survives world reloads until we clear it.</p>
 *
 * <p>The Chinese (and any future locale) pools/chains ship in DT's jar at
 * {@code data/dungeontrain/ain_localizations/&lt;locale&gt;/{pools,chains}/} — deliberately NOT under
 * {@code naming/}, so AIN's own listeners never auto-load them globally. Selectors are not
 * overlaid: they carry no translatable text (pure item-type→chain references, byte-identical to
 * AIN's) and continue to point at the same chain ids we overlay.</p>
 *
 * <p>Driven by {@link NarrativeLocaleWatcher}: {@link #select} is called with the resolved host
 * locale whenever it changes. Switching back to English re-injects the captured English baselines
 * so a mid-session language switch reverts immediately; a fresh world starts from AIN's own base
 * until the host resolves.</p>
 */
public final class AinLocaleOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Jar/datapack root for locale overlays (NOT {@code naming/}, so AIN never auto-loads it). */
    private static final String ROOT = "ain_localizations";
    private static final String POOLS_SUBDIR = "pools";
    private static final String CHAINS_SUBDIR = "chains";
    private static final String JSON_EXT = ".json";
    private static final String OVERLAY_PACK_ID = "dungeontrain:ain_i18n";
    private static final String AIN_NAMESPACE = "adventureitemnames";

    /** English baselines captured before the current overlay, keyed by id, for immediate revert. */
    private static final Map<ResourceLocation, NamePool> basePools = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> baseChains = new LinkedHashMap<>();
    /** Every id we overlaid this session, so the overlay is always cleared on revert (even ids with no base). */
    private static final Set<ResourceLocation> overlaidPoolIds = new LinkedHashSet<>();
    private static final Set<ResourceLocation> overlaidChainIds = new LinkedHashSet<>();
    /** Currently-applied locale ({@code ""} = none / English base). */
    private static String applied = "";

    private AinLocaleOverlay() {}

    /**
     * Overlay AIN naming for {@code locale} ({@code ""} → revert to English). No-op if already
     * applied. Runs on the server thread from {@link NarrativeLocaleWatcher}.
     */
    public static synchronized void select(ResourceManager resourceManager, String locale) {
        String loc = (locale == null) ? "" : locale;
        if (loc.equals(applied)) return;

        restoreBase(); // revert any current overlay to English first
        if (loc.isEmpty()) {
            applied = "";
            return;
        }
        int pools = overlayPools(resourceManager, loc);
        int chains = overlayChains(resourceManager, loc);
        applied = loc;
        LOGGER.info("[DungeonTrain] AIN item-name overlay applied for '{}' — {} pools, {} chains", loc, pools, chains);
    }

    /** Drop the overlay and re-inject English baselines so names revert immediately. */
    public static synchronized void restoreBase() {
        for (ResourceLocation id : overlaidPoolIds) {
            NamePool base = basePools.get(id);
            // Re-inject English into POOLS (if AIN had a base), then drop the overlay entry so the
            // localized pool won't re-apply on the next reload.
            if (base != null) {
                NameRegistry.putPoolInMemory(base, null);
            }
            NameRegistry.clearPoolOverlay(id);
        }
        for (ResourceLocation id : overlaidChainIds) {
            NameChain base = baseChains.get(id);
            if (base != null) {
                NameRegistry.putChainInMemory(base);
            }
            NameRegistry.clearChainOverlay(id);
        }
        basePools.clear();
        baseChains.clear();
        overlaidPoolIds.clear();
        overlaidChainIds.clear();
        applied = "";
    }

    private static int overlayPools(ResourceManager rm, String loc) {
        int loaded = 0;
        String dir = ROOT + "/" + loc + "/" + POOLS_SUBDIR;
        Map<ResourceLocation, Resource> resources = rm.listResources(dir, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation fallback = fallbackId(entry.getKey());
            try (InputStream in = entry.getValue().open()) {
                NamePool pool = NameCodec.parsePool(in, fallback);
                if (!overlaidPoolIds.contains(pool.id())) {
                    NameRegistry.pool(pool.id()).ifPresent(base -> basePools.put(pool.id(), base));
                }
                NameRegistry.putPoolInMemory(pool, OVERLAY_PACK_ID);
                overlaidPoolIds.add(pool.id());
                loaded++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] AIN overlay: failed to load pool {} — {}", entry.getKey(), e.toString());
            }
        }
        return loaded;
    }

    private static int overlayChains(ResourceManager rm, String loc) {
        int loaded = 0;
        String dir = ROOT + "/" + loc + "/" + CHAINS_SUBDIR;
        Map<ResourceLocation, Resource> resources = rm.listResources(dir, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation fallback = fallbackId(entry.getKey());
            try (InputStream in = entry.getValue().open()) {
                NameChain chain = NameCodec.parseChain(in, fallback);
                if (!overlaidChainIds.contains(chain.id())) {
                    NameRegistry.chain(chain.id()).ifPresent(base -> baseChains.put(chain.id(), base));
                }
                NameRegistry.putChainInMemory(chain);
                overlaidChainIds.add(chain.id());
                loaded++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] AIN overlay: failed to load chain {} — {}", entry.getKey(), e.toString());
            }
        }
        return loaded;
    }

    /**
     * Fallback id used only when a file omits its own {@code "id"} — {@code adventureitemnames:<basename>}
     * from the file's path tail. The bundled Chinese files all carry explicit ids, so this is a safety net.
     */
    private static ResourceLocation fallbackId(ResourceLocation file) {
        String path = file.getPath();
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        if (tail.endsWith(JSON_EXT)) {
            tail = tail.substring(0, tail.length() - JSON_EXT.length());
        }
        return ResourceLocation.fromNamespaceAndPath(AIN_NAMESPACE, tail);
    }
}
