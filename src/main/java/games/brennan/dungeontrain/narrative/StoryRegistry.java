package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory registry of every narrative {@link StoryFile} bundled in the mod
 * jar at {@code data/dungeontrain/narratives/stories/}.
 *
 * <p>Loaded once at {@link ServerStartingEvent} and re-loadable on demand via
 * the {@code /dungeontrain narrative reload} command. Mirrors the
 * {@code TrackVariantRegistry} pattern — server-thread synchronous, no async
 * background tasks, no datapack-reload listener (the legacy text content is
 * shipped-only, never overridden by player data packs).</p>
 *
 * <p>The sibling {@code unused/} folder is deliberately not scanned — that
 * content is held for future delivery surfaces (NPC dialogue, paper notes,
 * procedural pools) and should not appear in the lectern/book flow.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StoryRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_PREFIX = "/data/" + DungeonTrain.MOD_ID + "/narratives/stories/";
    private static final String JSON_EXT = ".json";

    private static final Map<ResourceLocation, StoryFile> STORIES = new LinkedHashMap<>();

    private StoryRegistry() {}

    /** Reload from the bundled resources. Safe to call outside event handlers. */
    public static synchronized void reload() {
        STORIES.clear();
        Set<String> basenames = BundledNbtScanner.scanBasenames(
            StoryRegistry.class, RESOURCE_PREFIX, LOGGER, JSON_EXT);
        int loaded = 0;
        int failed = 0;
        for (String basename : basenames) {
            String resourcePath = RESOURCE_PREFIX + basename + JSON_EXT;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                DungeonTrain.MOD_ID, "narratives/stories/" + basename);
            try (InputStream in = StoryRegistry.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warn("[DungeonTrain] Narrative: scanner found '{}' but resource stream is null — skipping",
                        basename);
                    failed++;
                    continue;
                }
                StoryFile story = StoryCodec.parse(in, id);
                STORIES.put(id, story);
                loaded++;
            } catch (StoryCodec.StoryParseException e) {
                LOGGER.error("[DungeonTrain] Narrative: failed to parse {} — {}", resourcePath, e.getMessage());
                failed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] Narrative: unexpected error reading {} — {}", resourcePath, e.toString());
                failed++;
            }
        }
        LOGGER.info("[DungeonTrain] Narrative registry loaded — {} stories from {} (failed: {})",
            loaded, RESOURCE_PREFIX, failed);
    }

    /** Snapshot of every registered story id, alphabetical. */
    public static synchronized List<ResourceLocation> ids() {
        List<ResourceLocation> out = new ArrayList<>(STORIES.keySet());
        out.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return out;
    }

    /** Snapshot of every registered story basename (path tail), alphabetical. */
    public static synchronized List<String> basenames() {
        List<String> out = new ArrayList<>();
        for (ResourceLocation rl : STORIES.keySet()) {
            String path = rl.getPath();
            int slash = path.lastIndexOf('/');
            out.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
        Collections.sort(out);
        return out;
    }

    /** Lookup by full ResourceLocation. */
    public static synchronized Optional<StoryFile> get(ResourceLocation id) {
        return Optional.ofNullable(STORIES.get(id));
    }

    /**
     * Lookup by short basename (e.g. {@code "augustus_park"}). Most callers
     * (commands, in-world placers) only know the basename, not the full
     * ResourceLocation, so this is the convenient form.
     */
    public static synchronized Optional<StoryFile> getByBasename(String basename) {
        for (Map.Entry<ResourceLocation, StoryFile> e : STORIES.entrySet()) {
            String path = e.getKey().getPath();
            int slash = path.lastIndexOf('/');
            String tail = slash >= 0 ? path.substring(slash + 1) : path;
            if (tail.equals(basename)) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    public static synchronized int count() {
        return STORIES.size();
    }

    /**
     * Total number of hand-authored letters across all mod stories — {@code Σ story.letters().size()}.
     * The fair-share / warm-up-ramp denominator {@code V} for the narrative-lectern discovery taper
     * (see {@code BookFactory.narrativeLecternChanceForWorld}).
     */
    public static synchronized int totalLetterCount() {
        int total = 0;
        for (StoryFile s : STORIES.values()) total += s.letters().size();
        return total;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (StoryRegistry.class) {
            STORIES.clear();
        }
    }
}
