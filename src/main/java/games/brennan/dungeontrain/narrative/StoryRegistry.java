package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of every narrative {@link StoryFile} at
 * {@code data/dungeontrain/narratives/stories/}.
 *
 * <p>Loaded through the vanilla {@link ResourceManager} server-data channel:
 * {@link NarrativeDataLoaders} registers {@link #load} as a reload listener on
 * {@code AddReloadListenerEvent}, so it fires at world load and on every
 * {@code /reload}. Because it goes through the datapack pipeline, a datapack (or
 * resource-translation pack) can override any bundled story by shipping
 * {@code data/dungeontrain/narratives/stories/<name>.json} — the highest-priority
 * pack wins per id. The {@code /dungeontrain narrative reload} command reloads it
 * on demand by handing over the server's live {@link ResourceManager}.</p>
 *
 * <p>The sibling {@code unused/} folder is deliberately not scanned — that
 * content is held for future delivery surfaces (NPC dialogue, paper notes,
 * procedural pools) and should not appear in the lectern/book flow.</p>
 */
public final class StoryRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** ResourceManager directory (namespace-relative, no leading/trailing slash). */
    private static final String DIR = "narratives/stories";
    private static final String JSON_EXT = ".json";

    private static final Map<ResourceLocation, StoryFile> STORIES = new LinkedHashMap<>();

    private StoryRegistry() {}

    /**
     * Reload from the given {@link ResourceManager} (bundled data + datapack
     * overrides). Called by the reload listener at world load / {@code /reload}
     * and by the {@code /dungeontrain narrative reload} command.
     */
    public static synchronized void load(ResourceManager resourceManager) {
        STORIES.clear();
        int loaded = 0;
        int failed = 0;
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(DIR, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            // Preserve the historical id shape (dungeontrain:narratives/stories/<name>) —
            // seen-book tracking and story-set achievements persist these ids, so only
            // the ".json" suffix is stripped from the full resource location.
            ResourceLocation id = stripJson(file);
            try (InputStream in = entry.getValue().open()) {
                StoryFile story = StoryCodec.parse(in, id);
                STORIES.put(id, story);
                loaded++;
            } catch (StoryCodec.StoryParseException e) {
                LOGGER.error("[DungeonTrain] Narrative: failed to parse {} — {}", file, e.getMessage());
                failed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] Narrative: unexpected error reading {} — {}", file, e.toString());
                failed++;
            }
        }
        LOGGER.info("[DungeonTrain] Narrative registry loaded — {} stories from '{}' (failed: {})",
            loaded, DIR, failed);
    }

    /** Drop every loaded story (called on server stop). */
    public static synchronized void clear() {
        STORIES.clear();
    }

    /** Strip the trailing {@code .json} from a resource location, keeping namespace + path. */
    private static ResourceLocation stripJson(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(
            file.getNamespace(), path.substring(0, path.length() - JSON_EXT.length()));
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
}
