package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuildCredits;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Remove a template — a carriage, a contents interior, a part, a track tile, a portal room — and
 * everything that names it.
 *
 * <h2>Why this is not just a file delete</h2>
 * <p>The editor's Remove used to delete the {@code .nbt} alone. A template is also its sidecars
 * ({@code .variants.json}, a carriage's {@code .parts.json}, an allow-list, a room's copies, the
 * container document filed under its plot key), its photo, its {@code weights.json} entry (weight,
 * gate, Stage link, display label, a room's door position), its credit, its own {@code .group.json}
 * and its slot in whichever group rolls it. Left behind, they are orphans — until somebody creates a
 * template under the same name and silently inherits all of it.</p>
 *
 * <h2>Reset versus remove</h2>
 * <p>When a bundled copy remains ({@code bundledRemains}) this is a <b>reset to shipped</b>: the
 * config-dir overlay goes — {@code .nbt}, sidecars, photo — so the shipped versions show through, and
 * the credit goes because whatever remains is not the downloaded build. The weights entry, the
 * group, the memberships, the registry entry and the source tree are left alone: the template still
 * exists, and {@code unset()} would rewrite the merged bundled+config map (in a dev checkout, into
 * {@code src/} as well), erasing shipped tuning.</p>
 *
 * <h2>Dev mode</h2>
 * <p>When {@link EditorDevMode} is on and nothing bundled remains, the source-tree twins go too.
 * Otherwise the bundled scan re-registers the template on the next launch — with all its sidecars
 * gone, which is the worst of both.</p>
 *
 * <h2>Order</h2>
 * <p>Files first, memory last, as {@link TrackVariantRename}: sidecars before the {@code .nbt}'s
 * source twin (the per-store source-tree helpers gate on that twin existing), memberships before the
 * registry drop (track groups are enumerated from the registry), the registry drop last so a failure
 * partway leaves a name that still resolves. Every file is best-effort with a warning; only the
 * {@code .nbt} itself aborts, and nothing in memory has changed by then.</p>
 */
public final class TemplateDelete {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TemplateDelete() {}

    /** What a delete did. {@code unregistered} is what a caller keys its plot restamp on. */
    public record Report(boolean nbtDeleted, boolean bundledRemains, boolean unregistered,
                         List<Path> removed, List<String> warnings) {

        /** A player-facing tail for the success line: how many linked files went with the template. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            int extras = Math.max(0, removed.size() - (nbtDeleted ? 1 : 0));
            if (extras > 0) sb.append(" and ").append(extras).append(" linked file")
                .append(extras == 1 ? "" : "s");
            if (!warnings.isEmpty()) sb.append(" (").append(warnings.size()).append(" could not be removed; see log)");
            return sb.toString();
        }
    }

    /** The file list and the memory steps a delete will take. Pure; see {@link #plan}. */
    record Plan(List<Path> files, boolean unsetWeights, boolean deleteOwnGroup,
                boolean rewriteMemberships, boolean unregister, boolean forgetRoomSize) {}

    // ---------- entry points ----------

    /** Remove a carriage shell. A built-in resets to its shipped copy and stays registered. */
    public static synchronized Report carriage(CarriageVariant variant) throws IOException {
        String id = variant.id();
        boolean bundledRemains = variant.isBuiltin();
        Path nbt = CarriageTemplateStore.fileFor(variant);
        Path sourceNbt = CarriageTemplateStore.sourceTreeAvailable()
            ? CarriageTemplateStore.sourceFileForId(id) : null;
        Plan plan = plan(BuilderPhotoPaths.Kind.CARRIAGE, "", id, nbt, sourceNbt,
            UserContentPaths::activeSubDir, TrackVariantRename::sourceDir,
            bundledRemains, EditorDevMode.isEnabled());
        return run("carriage", id, plan, bundledRemains, nbt,
            () -> CarriageTemplateStore.delete(variant),
            null, null,
            () -> CarriageWeights.unset(id),
            () -> BuildCredits.forget(BuilderPhotoPaths.Kind.CARRIAGE, "", id),
            null,
            () -> CarriageVariantRegistry.unregister(id),
            () -> {
                TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.CARRIAGE, "", id);
                StageBlockIndex.invalidateAll();
            });
    }

    /** Remove a contents interior, its own group and its slot in the group that rolls it. */
    public static synchronized Report contents(CarriageContents contents) throws IOException {
        String id = contents.id();
        boolean bundledRemains = contents.isBuiltin();
        Path nbt = CarriageContentsStore.fileFor(contents);
        Path sourceNbt = CarriageContentsStore.sourceTreeAvailable()
            ? CarriageContentsStore.sourceFileForId(id) : null;
        boolean devMode = EditorDevMode.isEnabled();
        Plan plan = plan(BuilderPhotoPaths.Kind.CONTENTS, "", id, nbt, sourceNbt,
            UserContentPaths::activeSubDir, TrackVariantRename::sourceDir, bundledRemains, devMode);
        return run("contents", id, plan, bundledRemains, nbt,
            () -> CarriageContentsStore.delete(contents),
            () -> CarriageContentsGroupStore.delete(id),
            () -> scrubContentsMemberships(id, devMode),
            () -> CarriageContentsWeights.unset(id),
            () -> BuildCredits.forget(BuilderPhotoPaths.Kind.CONTENTS, "", id),
            null,
            () -> CarriageContentsRegistry.unregister(id),
            () -> {
                TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.CONTENTS, "", id);
                CarriageContentsGroupStore.invalidate(id);
            });
    }

    /** Remove a part. One with a bundled fallback resets to it and stays registered. */
    public static synchronized Report part(CarriagePartKind kind, String rawName) throws IOException {
        String name = rawName.toLowerCase(Locale.ROOT);
        boolean bundledRemains = CarriagePartTemplateStore.bundled(kind, name);
        Path nbt = CarriagePartTemplateStore.fileFor(kind, name);
        Path sourceNbt = CarriagePartTemplateStore.sourceTreeAvailable()
            ? CarriagePartTemplateStore.sourceFileFor(kind, name) : null;
        Plan plan = plan(BuilderPhotoPaths.Kind.PART, kind.id(), name, nbt, sourceNbt,
            UserContentPaths::activeSubDir, TrackVariantRename::sourceDir,
            bundledRemains, EditorDevMode.isEnabled());
        return run("part " + kind.id(), name, plan, bundledRemains, nbt,
            () -> CarriagePartTemplateStore.delete(kind, name),
            null, null, null,
            () -> BuildCredits.forget(BuilderPhotoPaths.Kind.PART, kind.id(), name),
            null,
            () -> CarriagePartRegistry.unregister(kind, name),
            () -> TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.PART, kind.id(), name));
    }

    /**
     * Remove a track-side template — a tile, a pillar section, a tunnel, a portal room.
     *
     * <p>{@code default} is every kind's synthetic fallback: its config-dir overlay is cleared but
     * it is never unregistered, whether or not a bundled resource backs it.</p>
     */
    public static synchronized Report track(TrackKind kind, String rawName) throws IOException {
        String name = rawName.toLowerCase(Locale.ROOT);
        boolean room = kind == TrackKind.PORTAL_ROOM;
        BuilderPhotoPaths.Kind photoKind = room ? BuilderPhotoPaths.Kind.PORTAL_ROOM : BuilderPhotoPaths.Kind.TRACK;
        String subKind = room ? "" : kind.id();
        boolean bundledRemains = TrackKind.DEFAULT_NAME.equals(name) || TrackVariantStore.bundled(kind, name);
        Path nbt = TrackVariantStore.fileFor(kind, name);
        Path sourceNbt = TrackVariantStore.sourceTreeAvailable()
            ? TrackVariantStore.sourceFileFor(kind, name) : null;
        boolean devMode = EditorDevMode.isEnabled();
        Plan plan = plan(photoKind, subKind, name, nbt, sourceNbt,
            UserContentPaths::activeSubDir, TrackVariantRename::sourceDir, bundledRemains, devMode);
        return run(kind.id(), name, plan, bundledRemains, nbt,
            () -> TrackVariantStore.delete(kind, name),
            () -> TrackVariantGroupStore.delete(kind, name),
            () -> scrubTrackMemberships(kind, name, devMode),
            () -> TrackVariantWeights.unset(kind, name),
            () -> BuildCredits.forget(photoKind, subKind, name),
            () -> PortalRoomSizes.forget(name),
            () -> TrackVariantRegistry.unregister(kind, name),
            () -> {
                TemplateSidecars.invalidateCaches(photoKind, subKind, name);
                TrackVariantGroupStore.invalidate(kind, name);
            });
    }

    // ---------- the walk ----------

    @FunctionalInterface interface Step { void run() throws IOException; }

    private static Report run(String kindLabel, String id, Plan plan, boolean bundledRemains, Path nbt,
                              Step deleteNbt, @Nullable Step deleteOwnGroup, @Nullable Step scrubMemberships,
                              @Nullable Step unsetWeights, Step forgetCredit, @Nullable Step forgetRoomSize,
                              Step unregister, Step invalidate) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<Path> removed = new ArrayList<>();

        // Files: everything before the .nbt is best-effort; the .nbt itself may throw, and nothing
        // in memory has moved yet if it does; everything after it (its source twin) is best-effort.
        int at = plan.files().indexOf(nbt);
        List<Path> before = at < 0 ? plan.files() : plan.files().subList(0, at);
        List<Path> after = at < 0 ? List.of() : plan.files().subList(at + 1, plan.files().size());
        removed.addAll(deleteAll(before, warnings));
        boolean nbtDeleted = false;
        if (at >= 0) {
            boolean existed = Files.isRegularFile(nbt);
            deleteNbt.run();
            nbtDeleted = existed && !Files.exists(nbt);
            if (nbtDeleted) removed.add(nbt);
        }
        removed.addAll(deleteAll(after, warnings));

        // Memory and shared documents, each best-effort: an orphan is the status quo, a name nothing
        // resolves is worse, so no step here stops the ones after it.
        if (plan.deleteOwnGroup() && deleteOwnGroup != null) attempt("group sidecar", id, deleteOwnGroup, warnings);
        if (plan.rewriteMemberships() && scrubMemberships != null) attempt("group memberships", id, scrubMemberships, warnings);
        if (plan.unsetWeights() && unsetWeights != null) attempt("weights entry", id, unsetWeights, warnings);
        attempt("credit", id, forgetCredit, warnings);
        if (plan.forgetRoomSize() && forgetRoomSize != null) attempt("room size", id, forgetRoomSize, warnings);
        boolean unregistered = false;
        if (plan.unregister()) {
            attempt("registry entry", id, unregister, warnings);
            unregistered = true;
        }
        invalidate.run();

        LOGGER.info("[DungeonTrain] Removed {} template '{}': {} file(s) gone, bundled copy remains: {}, "
            + "unregistered: {}, warnings: {}", kindLabel, id, removed.size(), bundledRemains, unregistered,
            warnings.size());
        return new Report(nbtDeleted, bundledRemains, unregistered, List.copyOf(removed), List.copyOf(warnings));
    }

    private static void attempt(String what, String id, Step step, List<String> warnings) {
        try {
            step.run();
        } catch (Exception e) {
            String line = what + " for '" + id + "': " + e;
            warnings.add(line);
            LOGGER.warn("[DungeonTrain] Remove: could not clear {}", line);
        }
    }

    // ---------- pure ----------

    /**
     * Every file this delete will remove, in order, and which memory steps follow.
     *
     * <p>Order: the config-dir sidecars, their source twins, the photo, its source twin, the
     * {@code .nbt}, its source twin. Source twins only when {@code devMode} and nothing bundled
     * remains; {@code sourceDirFor} answers null for a subdirectory with no source tree.</p>
     *
     * <p>Pure so the one thing that decides what a Remove destroys can be pinned without a world.</p>
     */
    static Plan plan(BuilderPhotoPaths.Kind kind, String subKind, String id, Path nbt, @Nullable Path sourceNbt,
                     Function<String, Path> configDirFor, Function<String, Path> sourceDirFor,
                     boolean bundledRemains, boolean devMode) {
        boolean twins = devMode && !bundledRemains;
        List<Path> files = new ArrayList<>();
        List<TemplateSidecars.Sidecar> sidecars = TemplateSidecars.filesFor(kind, subKind, id);
        for (TemplateSidecars.Sidecar s : sidecars) {
            files.add(configDirFor.apply(s.subdir()).resolve(s.basename()));
        }
        if (twins) {
            for (TemplateSidecars.Sidecar s : sidecars) {
                Path dir = sourceDirFor.apply(s.subdir());
                if (dir != null) files.add(dir.resolve(s.basename()));
            }
        }
        files.add(BuilderPhotoPaths.withPng(nbt));
        if (twins && sourceNbt != null) files.add(BuilderPhotoPaths.withPng(sourceNbt));
        files.add(nbt);
        if (twins && sourceNbt != null) files.add(sourceNbt);

        boolean grouped = kind == BuilderPhotoPaths.Kind.CONTENTS
            || kind == BuilderPhotoPaths.Kind.TRACK || kind == BuilderPhotoPaths.Kind.PORTAL_ROOM;
        boolean weighted = grouped || kind == BuilderPhotoPaths.Kind.CARRIAGE;
        return new Plan(List.copyOf(files),
            weighted && !bundledRemains,
            grouped && !bundledRemains,
            grouped && !bundledRemains,
            !bundledRemains,
            kind == BuilderPhotoPaths.Kind.PORTAL_ROOM);
    }

    /**
     * Delete each of {@code files} that exists. Missing is the ordinary case and silent; anything
     * that refuses (a directory, a lock) is logged and added to {@code warnings}, and the walk goes
     * on. Returns the files that actually went.
     */
    static List<Path> deleteAll(List<Path> files, List<String> warnings) {
        List<Path> removed = new ArrayList<>();
        for (Path file : files) {
            try {
                if (Files.deleteIfExists(file)) removed.add(file);
            } catch (IOException e) {
                String line = file + ": " + e;
                warnings.add(line);
                LOGGER.warn("[DungeonTrain] Remove: could not delete {}", line);
            }
        }
        return removed;
    }

    // ---------- memberships ----------

    /** Drop {@code id} from every contents group that lists it; a group that does not is untouched. */
    private static void scrubContentsMemberships(String id, boolean devMode) throws IOException {
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            String parent = c.id();
            if (parent.equals(id)) continue;
            CarriageContentsGroup group = CarriageContentsGroupStore.get(parent).orElse(null);
            if (group == null) continue;
            CarriageContentsGroup next = group.withoutMember(id);
            if (next == group) continue;
            CarriageContentsGroupStore.save(parent, next);
            if (devMode && CarriageContentsGroupStore.sourceTreeAvailable()) {
                CarriageContentsGroupStore.saveToSource(parent, next);
            }
            LOGGER.info("[DungeonTrain] Remove contents '{}': dropped from group '{}'", id, parent);
        }
    }

    /** Drop {@code name} from every group of {@code kind} that lists it. */
    private static void scrubTrackMemberships(TrackKind kind, String name, boolean devMode) throws IOException {
        for (String parent : TrackVariantRegistry.namesFor(kind)) {
            if (parent.equals(name)) continue;
            TrackVariantGroup group = TrackVariantGroupStore.get(kind, parent).orElse(null);
            if (group == null) continue;
            TrackVariantGroup next = group.withoutMember(name);
            if (next == group) continue;
            TrackVariantGroupStore.save(kind, parent, next);
            if (devMode && TrackVariantGroupStore.sourceTreeAvailable()) {
                TrackVariantGroupStore.saveToSource(kind, parent, next);
            }
            LOGGER.info("[DungeonTrain] Remove {}:{}: dropped from group '{}'", kind.id(), name, parent);
        }
    }
}
