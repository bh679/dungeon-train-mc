package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory index of which part-template names exist on disk for each
 * {@link CarriagePartKind}. Feeds command-completion for
 * {@code /dungeontrain editor part ...} and scoping checks when wiring a
 * carriage's {@code parts.json} assignment.
 *
 * <p>Populated at {@link ServerStartingEvent} from two tiers, matching
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s approach:
 * <ol>
 *   <li><b>Bundled manifests</b> — {@code /data/dungeontrain/parts/<kind>/manifest.json}
 *       (optional JSON array of names) lists part NBTs that ship with the jar.</li>
 *   <li><b>Config directories</b> — {@code config/dungeontrain/parts/<kind>/*.nbt}
 *       scanned for filenames (minus the {@code .nbt} suffix).</li>
 * </ol>
 *
 * <p>The reserved name {@link CarriagePartKind#NONE} is never registered — it
 * is a sentinel handled by {@link games.brennan.dungeontrain.train.CarriagePartPlacer#placeAt}
 * as "stamp nothing for this kind". {@link #names(CarriagePartKind)} prepends
 * it so it always appears in completions regardless of disk state.</p>
 *
 * <p>On {@link ServerStoppedEvent} the index is cleared and so are the
 * per-template and per-assignment caches, so a subsequent world load with
 * different {@link games.brennan.dungeontrain.train.CarriageDims} cannot see
 * stale entries.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CarriagePartRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Same name shape as {@code CarriageVariant.NAME_PATTERN}: lowercase, digits, underscore, 1-32 chars. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    /**
     * Insertion-ordered per-kind name set. Ordering matters: the parts grid's
     * X slot for {@code (kind, name)} is derived from this collection's index
     * via {@link CarriagePartEditor#plotOrigin}, so any reordering shifts
     * already-stamped plots to new world positions and the user sees their
     * authored content land on the wrong slot. Insertion-order means new
     * names appended at runtime (e.g. via the editor's "New" button) never
     * displace existing names — the new plot lands at the next free slot
     * past the last registered name. {@link #reload} sorts the on-disk set
     * alphabetically once at server start so the order is deterministic
     * across filesystem implementations.
     */
    private static final Map<CarriagePartKind, LinkedHashSet<String>> NAMES = new EnumMap<>(CarriagePartKind.class);
    static {
        for (CarriagePartKind k : CarriagePartKind.values()) NAMES.put(k, new LinkedHashSet<>());
    }

    private CarriagePartRegistry() {}

    /**
     * Snapshot of every registered name for {@code kind}, with the
     * {@link CarriagePartKind#NONE} sentinel prepended so command-completion
     * always offers the "skip this kind" option.
     */
    public static synchronized List<String> names(CarriagePartKind kind) {
        List<String> out = new ArrayList<>(NAMES.get(kind).size() + 1);
        out.add(CarriagePartKind.NONE);
        out.addAll(NAMES.get(kind));
        return out;
    }

    /** Names without the {@link CarriagePartKind#NONE} sentinel — for list output. */
    public static synchronized List<String> registeredNames(CarriagePartKind kind) {
        return Collections.unmodifiableList(new ArrayList<>(NAMES.get(kind)));
    }

    public static synchronized boolean isKnown(CarriagePartKind kind, String name) {
        if (name == null) return false;
        if (CarriagePartKind.NONE.equals(name)) return true;
        return NAMES.get(kind).contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Add {@code name} to {@code kind}'s registered set. No-ops on the
     * {@link CarriagePartKind#NONE} sentinel and on names that don't match
     * {@link #NAME_PATTERN}. Returns true iff the set did not already contain
     * the name (same contract as {@link java.util.Set#add}).
     */
    public static synchronized boolean register(CarriagePartKind kind, String name) {
        if (name == null) return false;
        if (CarriagePartKind.NONE.equals(name)) return false;
        String norm = name.toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(norm).matches()) return false;
        return NAMES.get(kind).add(norm);
    }

    public static synchronized boolean unregister(CarriagePartKind kind, String name) {
        if (name == null) return false;
        if (CarriagePartKind.NONE.equals(name)) return false;
        return NAMES.get(kind).remove(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized void clear() {
        for (CarriagePartKind k : CarriagePartKind.values()) NAMES.get(k).clear();
    }

    public static synchronized void reload() {
        clear();
        int bundled = loadBundledScans();
        int config = loadConfigDirs();
        LOGGER.info("[DungeonTrain] Part registry loaded — {} bundled + {} config parts across {} kinds",
            bundled, config, CarriagePartKind.values().length);
    }

    /**
     * Discover bundled part templates by scanning the classpath at
     * {@code /data/dungeontrain/parts/<kind>/} for {@code .nbt} files —
     * supersedes the hand-maintained {@code manifest.json} that this method
     * used to read. The legacy manifest is still cross-checked for
     * transitional drift detection: any disagreement between scan and
     * manifest produces a WARN, surfacing the drift before the manifest
     * files are deleted in a follow-up commit.
     */
    private static int loadBundledScans() {
        int added = 0;
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            String prefix = "/data/dungeontrain/parts/" + kind.id() + "/";
            Set<String> scanned = BundledNbtScanner.scanBasenames(
                CarriagePartRegistry.class, prefix, LOGGER);
            Set<String> manifest = BundledNbtScanner.readManifestBasenames(
                CarriagePartRegistry.class, prefix, "manifest.json", LOGGER);
            BundledNbtScanner.warnDrift("parts/" + kind.id(), scanned, manifest, LOGGER);
            for (String id : scanned) {
                if (register(kind, id)) added++;
            }
        }
        return added;
    }

    private static int loadConfigDirs() {
        int added = 0;
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            Path dir = CarriagePartTemplateStore.directory(kind);
            if (!Files.isDirectory(dir)) continue;
            // Collect filenames first and sort alphabetically — DirectoryStream
            // is filesystem-ordered (APFS by inode, ext4 by hash), so without
            // an explicit sort the registry ordering — and therefore the parts
            // grid's X slot assignments — would differ between machines and
            // even between sessions on the same machine.
            List<String> ids = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
                for (Path file : stream) {
                    String fn = file.getFileName().toString();
                    if (!fn.endsWith(".nbt")) continue;
                    ids.add(fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT));
                }
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to scan parts directory {}: {}", dir, e.toString());
            }
            Collections.sort(ids);
            for (String id : ids) {
                if (register(kind, id)) added++;
            }
        }
        return added;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
        CarriagePartTemplateStore.clearCache();
        CarriageVariantPartsStore.clearCache();
        CarriagePartVariantBlocks.clearCache();
        CarriagePartEditor.clearSessions();
    }

    /**
     * Phase-2 adapter — exposes the parts registry through the unified
     * {@link TemplateRegistry} surface. Cached per
     * {@link CarriagePartKind} since each kind has its own
     * {@code LinkedHashSet} of names (and the X-slot ordering depends on
     * insertion order for that specific kind).
     *
     * <p>{@link TemplateRegistry#builtins()} is empty for parts because
     * there are no shipped sentinel names today — every registered name
     * is a user-authored variant. Mirrors
     * {@link Template.PartModel#isBuiltin()} returning false.</p>
     */
    private static final EnumMap<CarriagePartKind, TemplateRegistry<Template.PartModel>> ADAPTERS
        = new EnumMap<>(CarriagePartKind.class);
    static {
        for (CarriagePartKind k : CarriagePartKind.values()) ADAPTERS.put(k, makeAdapter(k));
    }

    private static TemplateRegistry<Template.PartModel> makeAdapter(CarriagePartKind kind) {
        return new TemplateRegistry<>() {
            @Override public TemplateKind kind() { return TemplateKind.PART; }

            @Override
            public List<Template.PartModel> all() {
                List<String> names = registeredNames(kind);
                List<Template.PartModel> out = new ArrayList<>(names.size());
                for (String n : names) out.add(new Template.PartModel(kind, n));
                return out;
            }

            @Override
            public List<Template.PartModel> builtins() { return List.of(); }

            @Override
            public List<Template.PartModel> customs() { return all(); }

            @Override
            public Optional<Template.PartModel> find(String id) {
                if (!isKnown(kind, id)) return Optional.empty();
                return Optional.of(new Template.PartModel(kind, id));
            }

            @Override public void reload() { CarriagePartRegistry.reload(); }
            @Override public void clear() { CarriagePartRegistry.clear(); }
        };
    }

    public static TemplateRegistry<Template.PartModel> adapter(CarriagePartKind kind) {
        return ADAPTERS.get(kind);
    }

    /**
     * Phase-3 record-shaped overload: {@link #adapter(CarriagePartKind)}
     * keyed via the
     * {@link games.brennan.dungeontrain.template.CarriagePartTemplateId}
     * record.
     */
    public static TemplateRegistry<Template.PartModel> adapter(games.brennan.dungeontrain.template.CarriagePartTemplateId id) {
        return ADAPTERS.get(id.kind());
    }
}
