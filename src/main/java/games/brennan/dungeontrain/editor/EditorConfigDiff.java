package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Snapshot / diff / restore over the editor's authored config tree — the
 * mechanism behind undoing <b>menu</b> changes: weights, stage links and gates,
 * loot tables, contents allow-lists, part assignments, group references.
 *
 * <p><b>Why files rather than per-op inverses.</b> That state is spread across
 * some twenty stores ({@code CarriageWeights}, {@link StageStore},
 * {@link LootPrefabStore}, {@link ContainerContentsStore}, the allow-lists, the
 * group stores…). Teaching each operation to invert itself is a surface that
 * grows with every menu row added. But all of them persist into one small, flat
 * tree of JSON under the active package's write directory — a few dozen
 * kilobytes. Snapshotting that tree around an operation covers every store at
 * once, with no per-store knowledge, and covers menu rows added later on the day
 * they land.</p>
 *
 * <p><b>What this deliberately does not cover.</b> Edits that mutate a cached
 * object and defer their write to {@code /dt save} — block-variant sidecars,
 * chiefly — never touch a file at edit time, so a file diff sees nothing for
 * them. Those are captured in memory instead, by
 * {@link EditorEditRecorder#notePendingSidecar}. The two mechanisms are
 * complementary and both feed the same {@link EditorEditHistory.Step}.</p>
 *
 * <p>Scans run once per user-driven action — a command or a menu click — never
 * on a tick loop.</p>
 */
public final class EditorConfigDiff {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorConfigDiff() {}

    /**
     * Every regular file under the editor's active write directory, keyed by
     * path relative to that root, valued by contents.
     *
     * <p>Returns an empty map if the root does not exist yet (a fresh install
     * that has authored nothing) — which diffs correctly against a later scan
     * that finds files, so a first-ever save is still undoable.</p>
     */
    public static Map<String, String> scan() {
        return scan(UserContentPaths.activeWriteDir());
    }

    /** Testable form of {@link #scan()} — same contract, explicit root. */
    public static Map<String, String> scan(Path root) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    out.put(relative(root, file), Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // A binary template (.nbt) is not readable as UTF-8 text and
                    // is not menu state, so skipping it is correct rather than
                    // merely tolerable — geometry is covered by the block-cell
                    // path instead.
                    LOGGER.debug("[DungeonTrain] EditorConfigDiff: skipping unreadable {}: {}",
                        file, e.toString());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("[DungeonTrain] EditorConfigDiff: scan of {} failed — menu changes in this "
                + "action will not be undoable: {}", root, e.toString());
        }
        return out;
    }

    /**
     * Files that differ between two scans, as history snapshots. A path present
     * in only one side is recorded with {@code null} on the other, so undoing a
     * file's creation deletes it rather than leaving an empty document behind.
     */
    public static List<EditorEditHistory.FileSnapshot> diff(Map<String, String> before,
                                                            Map<String, String> after) {
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<EditorEditHistory.FileSnapshot> out = new ArrayList<>();
        for (String path : paths) {
            String b = before.get(path);
            String a = after.get(path);
            if (b == null ? a == null : b.equals(a)) continue;
            out.add(new EditorEditHistory.FileSnapshot(path, b, a));
        }
        return out;
    }

    /**
     * Put one file back to {@code json} — writing it, or deleting it when
     * {@code json} is null — and drop the cache of whichever store owns it.
     *
     * @return false when the write failed; the caller reports a partial undo.
     */
    public static boolean restore(EditorEditHistory.FileSnapshot snapshot) {
        boolean ok = restore(UserContentPaths.activeWriteDir(), snapshot);
        if (ok) invalidateFor(snapshot.relPath());
        return ok;
    }

    /**
     * The file half of {@link #restore(EditorEditHistory.FileSnapshot)}, against
     * an explicit root and with <b>no</b> cache invalidation.
     *
     * <p>Split out so it can be unit-tested: invalidation reaches into live
     * static stores — {@code CarriageWeights.reload()} and friends — and firing
     * that from a test loads real config into shared state that other suites
     * then read. Callers in production want
     * {@link #restore(EditorEditHistory.FileSnapshot)}, which does both.</p>
     */
    static boolean restore(Path root, EditorEditHistory.FileSnapshot snapshot) {
        Path file = root.resolve(snapshot.relPath());
        try {
            if (snapshot.beforeJson() == null) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, snapshot.beforeJson(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] EditorConfigDiff: restore of {} failed: {}",
                snapshot.relPath(), e.toString());
            return false;
        }
        return true;
    }

    /**
     * Drop the in-memory cache of the store that owns {@code relPath}, so the
     * next read comes from the file just restored.
     *
     * <p>Targeted rather than a blanket {@code TemplateStores.reloadCachesOnly()}:
     * that is the package-switch path — it re-runs the content importer, reloads
     * every registry, discards unsaved in-memory edits held in other stores, and
     * clears the undo history itself, which would strand an undo half-way
     * through.</p>
     *
     * <p>An unrecognised path clears the common editor caches and logs, so a
     * store added later degrades to "slightly heavy-handed" rather than "stale
     * state after undo".</p>
     */
    private static void invalidateFor(String relPath) {
        String path = relPath.replace('\\', '/');

        if (path.equals("weights.json")) {
            // Weights, spawn gates and stage links all live in this one file, and
            // three registries read it.
            games.brennan.dungeontrain.train.CarriageWeights.reload();
            games.brennan.dungeontrain.train.CarriageContentsWeights.reload();
            games.brennan.dungeontrain.track.variant.TrackVariantWeights.reload();
            return;
        }
        if (path.equals("stages.json")) {
            StageStore.reload();
            return;
        }
        if (path.startsWith("prefabs/loot/")) {
            LootPrefabStore.reload();
            return;
        }
        if (path.startsWith("containers/")) {
            ContainerContentsStore.clearCache();
            return;
        }
        if (path.endsWith(".group.json")) {
            TrackVariantGroupStore.clearCache();
            return;
        }
        if (path.endsWith(".parts.json")) {
            CarriageVariantPartsStore.clearCache();
            return;
        }
        if (path.endsWith(".contents.json")) {
            CarriageVariantContentsAllowStore.clearCache();
            PortalRoomContentsAllowStore.clearCache();
            return;
        }
        if (path.startsWith("contents/")) {
            CarriageContentsStore.clearCache();
            CarriageContentsVariantBlocks.clearCache();
            return;
        }
        if (path.startsWith("portals/") || path.startsWith("tracks/") || path.startsWith("pillars/")
            || path.startsWith("tunnels/")) {
            games.brennan.dungeontrain.track.variant.TrackVariantStore.clearCache();
            games.brennan.dungeontrain.track.variant.TrackVariantBlocks.clearCache();
            return;
        }
        if (path.startsWith("templates/")) {
            CarriageTemplateStore.clearCache();
            CarriageVariantBlocks.clearCache();
            CarriagePartTemplateStore.clearCache();
            CarriagePartVariantBlocks.clearCache();
            return;
        }

        LOGGER.info("[DungeonTrain] EditorConfigDiff: no cache rule for '{}' — clearing the common "
            + "editor caches. Add a rule if this path is a new store.", path);
        clearCommonCaches();
    }

    /** The fallback sweep — every editor cache that reads from the config tree. */
    private static void clearCommonCaches() {
        CarriageTemplateStore.clearCache();
        CarriageContentsStore.clearCache();
        CarriageVariantBlocks.clearCache();
        CarriageVariantPartsStore.clearCache();
        CarriageVariantContentsAllowStore.clearCache();
        CarriagePartTemplateStore.clearCache();
        CarriagePartVariantBlocks.clearCache();
        CarriageContentsVariantBlocks.clearCache();
        PillarTemplateStore.clearCache();
        ContainerContentsStore.clearCache();
        TrackVariantGroupStore.clearCache();
        PortalRoomContentsAllowStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks.clearCache();
    }

    /** Forward-slash relative path, so keys are stable across platforms. */
    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
