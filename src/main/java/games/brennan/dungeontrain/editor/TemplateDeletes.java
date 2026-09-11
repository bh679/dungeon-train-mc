package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageWeights;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everything a template delete has to take with it, beyond the {@code .nbt} the store itself removes.
 *
 * <p>Each template family's save path writes a small constellation of files — the {@code .nbt}, its
 * variants sidecar, its container-contents sidecar, a {@code weights.json} key, group membership —
 * and in dev mode writes every one of them through to the source tree as well
 * ({@link CarriageContentsEditor#save} and friends). The stores' {@code delete} methods only ever
 * removed the user-tier {@code .nbt}, so a dev-mode delete left the bundled copy, its weight and its
 * group slot behind in {@code src/main/resources} — dead config that
 * {@code BundledCarriageAndContentsWeightsTest} rightly rejects. This class is the delete-side mirror
 * of that write-through.</p>
 *
 * <p>Two tiers, gated differently:</p>
 * <ul>
 *   <li><b>User tier</b> (config-dir sidecars, in-memory weights entry, group slot) — always removed;
 *       a delete is a delete regardless of dev mode.</li>
 *   <li><b>Source tier</b> — only when {@link EditorDevMode#isEnabled()} and the family's store reports
 *       a writable source tree, and only for templates that exist because an author saved them:
 *       customs for contents and shells (a built-in's {@code reset} means "drop the override, revert to
 *       the fallback", and its bundled copy stays), every named track variant (the command already
 *       refuses {@code default}), and every part (parts have no built-in/custom split — in dev mode the
 *       bundled copy is simply the last save).</li>
 * </ul>
 *
 * <p>Best-effort throughout: each step is isolated, a failure is logged and reported in the
 * {@link Report} without stopping the others, and nothing here can undo the {@code .nbt} delete the
 * caller has already performed.</p>
 *
 * <p>Not {@link TemplateSidecars#filesFor}, which names the same family of files for a different job:
 * it resolves user-tier paths so a build can be carried between installs, and knows nothing about the
 * source tree, the weights entry or the group slot. Deletes go through each owning store instead, so
 * every store's own cache is invalidated along with its file.</p>
 */
public final class TemplateDeletes {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What was removed and what could not be, for the command layer's reply line. */
    public record Report(List<String> removed, List<String> failures) {
        public static final Report EMPTY = new Report(List.of(), List.of());

        /** One chat line, or {@code ""} when nothing beyond the template file was touched. */
        public String summaryLine() {
            if (removed.isEmpty() && failures.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            if (!removed.isEmpty()) sb.append(" Also removed: ").append(String.join(", ", removed)).append('.');
            if (!failures.isEmpty()) sb.append(" Could not remove: ").append(String.join(", ", failures)).append('.');
            return sb.toString();
        }
    }

    /** Mutable accumulator behind {@link Report}; never escapes this class. */
    private static final class Steps {
        private final List<String> removed = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        void run(String label, IoStep step) {
            try {
                if (step.run()) removed.add(label);
            } catch (Exception e) {
                failures.add(label);
                LOGGER.warn("[DungeonTrain] Template delete: could not remove {}: {}", label, e.toString());
            }
        }

        Report report() {
            return new Report(Collections.unmodifiableList(removed), Collections.unmodifiableList(failures));
        }
    }

    @FunctionalInterface
    private interface IoStep {
        /** @return whether anything was actually removed */
        boolean run() throws IOException;
    }

    private TemplateDeletes() {}

    // ---------- contents ----------

    /**
     * Companion cleanup for {@link CarriageContentsStore#delete}. Source-tier cleanup applies to customs
     * only — see the class javadoc.
     */
    public static Report contents(CarriageContents contents) {
        String id = contents.id().toLowerCase(Locale.ROOT);
        String plotKey = BlockVariantPlot.contentsKey(id);
        Steps steps = new Steps();

        steps.run("variants sidecar", () -> CarriageContentsVariantBlocks.delete(contents));
        steps.run("container sidecar", () -> ContainerContentsStore.deleteConfig(plotKey));
        // Weight and group edits write both tiers by design (their stores mirror on every write), so a
        // built-in's reset — which keeps its bundled copy — keeps its shipped weight and group slot too.
        boolean custom = !contents.isBuiltin();
        if (custom) {
            steps.run("weights entry", () -> CarriageContentsWeights.unset(id));
            steps.run("group membership", () -> stripFromContentsGroups(id));
            steps.run("own group file", () -> CarriageContentsGroupStore.delete(id));
        }

        if (custom && sourceCleanupOn(CarriageContentsStore.sourceTreeAvailable())) {
            steps.run("bundled template", () -> deleteQuietly(CarriageContentsStore.sourceFileForId(id)));
            steps.run("bundled variants sidecar", () -> deleteQuietly(CarriageContentsVariantBlocks.sourcePathForId(id)));
            steps.run("bundled container sidecar", () -> ContainerContentsStore.deleteFromSource(plotKey));
        }
        return steps.report();
    }

    /**
     * Take {@code childId} out of every contents group that lists it. A parent left with no members loses
     * its group file (the parent reverts to a leaf), exactly as {@code group remove} does by hand. Both
     * store writes carry through to the source tree on their own.
     *
     * @return whether any group changed
     */
    static boolean stripFromContentsGroups(String childId) throws IOException {
        Map<String, Optional<CarriageContentsGroup>> edits = contentsGroupEdits(
            childId, CarriageContentsGroupStore.knownParentIds(), CarriageContentsGroupStore::get);
        for (Map.Entry<String, Optional<CarriageContentsGroup>> e : edits.entrySet()) {
            if (e.getValue().isEmpty()) CarriageContentsGroupStore.delete(e.getKey());
            else CarriageContentsGroupStore.save(e.getKey(), e.getValue().get());
        }
        return !edits.isEmpty();
    }

    /**
     * The pure half of {@link #stripFromContentsGroups}: which parents change, and to what. A value of
     * {@code Optional.empty()} means the parent lost its last member and its group file goes.
     * Parents that do not list {@code childId} — and {@code childId} itself — are absent from the map.
     */
    static Map<String, Optional<CarriageContentsGroup>> contentsGroupEdits(
        String childId, Collection<String> parents, Function<String, Optional<CarriageContentsGroup>> lookup
    ) {
        Map<String, Optional<CarriageContentsGroup>> edits = new LinkedHashMap<>();
        for (String parent : parents) {
            if (parent.equals(childId)) continue;
            Optional<CarriageContentsGroup> group = lookup.apply(parent);
            if (group.isEmpty()) continue;
            CarriageContentsGroup updated = group.get().withoutMember(childId);
            if (updated.members().size() == group.get().members().size()) continue;
            edits.put(parent, updated.members().isEmpty() ? Optional.empty() : Optional.of(updated));
        }
        return edits;
    }

    // ---------- shells ----------

    /**
     * Companion cleanup for {@link CarriageTemplateStore#delete}. Source-tier cleanup applies to customs
     * only — see the class javadoc.
     */
    public static Report carriage(CarriageVariant variant) {
        String id = variant.id().toLowerCase(Locale.ROOT);
        String plotKey = BlockVariantPlot.carriageKey(id);
        Steps steps = new Steps();
        boolean sourceToo = !variant.isBuiltin() && sourceCleanupOn(CarriageTemplateStore.sourceTreeAvailable());

        // The parts-assignment and contents-allow stores mirror their own source copies, but only while
        // the bundled .nbt still exists (their shipsWithGame gate) — so they go before the .nbt does.
        steps.run("parts assignment", () -> CarriageVariantPartsStore.delete(variant));
        steps.run("contents allow-list", () -> CarriageVariantContentsAllowStore.delete(variant));
        steps.run("variants sidecar", () -> CarriageVariantBlocks.delete(variant));
        steps.run("container sidecar", () -> ContainerContentsStore.deleteConfig(plotKey));
        // Same rule as contents: the weights store mirrors every write, so only a custom loses its key.
        if (!variant.isBuiltin()) steps.run("weights entry", () -> CarriageWeights.unset(id));

        if (sourceToo) {
            steps.run("bundled template", () -> deleteQuietly(CarriageTemplateStore.sourceFileForId(id)));
            steps.run("bundled variants sidecar", () -> deleteQuietly(CarriageVariantBlocks.sourcePathForVariant(variant)));
            steps.run("bundled container sidecar", () -> ContainerContentsStore.deleteFromSource(plotKey));
        }
        return steps.report();
    }

    // ---------- parts ----------

    /** Companion cleanup for {@link CarriagePartTemplateStore#delete}. */
    public static Report part(CarriagePartKind kind, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String plotKey = BlockVariantPlot.partKey(kind, key);
        Steps steps = new Steps();

        steps.run("variants sidecar", () -> CarriagePartVariantBlocks.delete(kind, key));
        steps.run("container sidecar", () -> ContainerContentsStore.deleteConfig(plotKey));

        if (sourceCleanupOn(CarriagePartTemplateStore.sourceTreeAvailable())) {
            steps.run("bundled template", () -> deleteQuietly(CarriagePartTemplateStore.sourceFileFor(kind, key)));
            steps.run("bundled variants sidecar", () -> deleteQuietly(CarriagePartVariantBlocks.sourcePathFor(kind, key)));
            steps.run("bundled container sidecar", () -> ContainerContentsStore.deleteFromSource(plotKey));
        }
        return steps.report();
    }

    // ---------- track-side variants ----------

    /**
     * Companion cleanup for {@link TrackVariantStore#delete} of a named variant — {@code default}
     * included: outside dev mode that drops the config-dir override and the bundled copy stays; in dev
     * mode the bundled copy goes too and the kind falls back to its built-in geometry. Not for the
     * {@code default} resets the pillar/tunnel/track <b>stores</b> perform on their own.
     */
    public static Report track(TrackKind kind, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String plotKey = ContainerContentsStore.trackPlotKey(kind, key);
        Steps steps = new Steps();

        steps.run("variants sidecar", () -> TrackVariantBlocks.delete(kind, key));
        steps.run("container sidecar", () -> ContainerContentsStore.deleteConfig(plotKey));
        steps.run("weights entry", () -> TrackVariantWeights.unset(kind, key));
        steps.run("group membership", () -> stripFromTrackGroups(kind, key));
        steps.run("own group file", () -> TrackVariantGroupStore.delete(kind, key));

        if (sourceCleanupOn(TrackVariantStore.sourceTreeAvailable())) {
            steps.run("bundled template", () -> deleteQuietly(TrackVariantStore.sourceFileFor(kind, key)));
            steps.run("bundled variants sidecar", () -> deleteQuietly(TrackVariantBlocks.sourcePathFor(kind, key)));
            steps.run("bundled container sidecar", () -> ContainerContentsStore.deleteFromSource(plotKey));
        }
        return steps.report();
    }

    /**
     * Track-side twin of {@link #stripFromContentsGroups}. Parents are found through the registry
     * because the track group store keeps no parent index of its own.
     *
     * @return whether any group changed
     */
    static boolean stripFromTrackGroups(TrackKind kind, String childId) throws IOException {
        Map<String, Optional<TrackVariantGroup>> edits = trackGroupEdits(
            childId, new ArrayList<>(TrackVariantRegistry.namesFor(kind)), p -> TrackVariantGroupStore.get(kind, p));
        for (Map.Entry<String, Optional<TrackVariantGroup>> e : edits.entrySet()) {
            if (e.getValue().isEmpty()) TrackVariantGroupStore.delete(kind, e.getKey());
            else TrackVariantGroupStore.save(kind, e.getKey(), e.getValue().get());
        }
        return !edits.isEmpty();
    }

    /** Track-side twin of {@link #contentsGroupEdits}; the two group records share no supertype. */
    static Map<String, Optional<TrackVariantGroup>> trackGroupEdits(
        String childId, Collection<String> parents, Function<String, Optional<TrackVariantGroup>> lookup
    ) {
        Map<String, Optional<TrackVariantGroup>> edits = new LinkedHashMap<>();
        for (String parent : parents) {
            if (parent.equals(childId)) continue;
            Optional<TrackVariantGroup> group = lookup.apply(parent);
            if (group.isEmpty()) continue;
            TrackVariantGroup updated = group.get().withoutMember(childId);
            if (updated.members().size() == group.get().members().size()) continue;
            edits.put(parent, updated.members().isEmpty() ? Optional.empty() : Optional.of(updated));
        }
        return edits;
    }

    // ---------- shared ----------

    private static boolean sourceCleanupOn(boolean sourceTreeAvailable) {
        return EditorDevMode.isEnabled() && sourceTreeAvailable;
    }

    /** {@code deleteIfExists} that tolerates the null the sidecar path helpers return outside a checkout. */
    private static boolean deleteQuietly(Path file) throws IOException {
        if (file == null) return false;
        boolean existed = Files.deleteIfExists(file);
        if (existed) LOGGER.info("[DungeonTrain] Template delete: removed bundled {}", file);
        return existed;
    }
}
