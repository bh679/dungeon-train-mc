package games.brennan.dungeontrain.editor.workbench;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.portal.PortalTestPending;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.track.TrackTestPiece;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import games.brennan.dungeontrain.train.CarriageTestSession;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Test the Carriage for a Workbench build, without committing it: the build is installed as the kind
 * the relay gave it under a reserved id, in a scratch folder of its own, for exactly as long as a test
 * stands on it — then unregistered and deleted.
 *
 * <p>Why a scratch install rather than an override: every test path resolves its template by id
 * through a registry, then reads blocks and sidecars from a dozen per-store caches that only ever load
 * from files. The one place all of them meet is {@code UserContentPaths.searchDirs}, so a live scratch
 * folder is put at the front of it, and the stores' saves are pointed at that folder while the install
 * writes. Every test — block variants, mob cells, reseed, focus reseed — then runs unchanged.</p>
 *
 * <p>Nothing persistent is touched: no shared weight file (a scratch id weighs 0 everywhere, so no
 * train can ever draw it, and a room's mode is held here in memory), no credit, no loot prefab library
 * (chests linked to prefabs this install does not have stay empty, and the test says so).</p>
 *
 * <p>Lifetime is by reference, not by hook: {@link #sweep} drops any scratch no test session or waiting
 * dimensional-carriage press names. A reseed runs back-then-in inside one command, so the scratch is
 * still named when the sweep next looks; a failed press leaves it unnamed and it goes on the next one.
 * The whole folder is wiped at server start and stop, for a crash mid-test.</p>
 */
public final class WorkbenchTestScratch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Every scratch id starts with this — short enough that the id fits every name pattern (32). */
    public static final String ID_PREFIX = "wbtest_";

    /** Under the player-data root — never inside a package, so a package save never copies it. */
    static final String DIR = "workbench-test";

    /** Which existing test stands a build up. */
    public enum Scene { CARRIAGE, CONTENTS, WHOLE_GROUP, TRACK, PORTAL_ROOM }

    /**
     * A build standing as a scratch template.
     *
     * @param size      the shell or contents size it was measured at; null for the other scenes
     * @param trackKind the piece of the line, for {@link Scene#TRACK} and {@link Scene#PORTAL_ROOM}
     * @param mode      the room mode its sidecar weights carried, or null
     * @param prefabsMissing loot prefabs it links that this install does not have
     */
    public record Installed(String stagedId, String scratchId, String buildName, Scene scene,
                            BuilderPhotoPaths.Kind kind, String subKind, TrackKind trackKind,
                            ContentsSize size, String mode, int prefabsMissing) {

        /** The track test's model id, for {@link Scene#TRACK}. */
        public Optional<String> trackModelId() {
            if (trackKind == null) return Optional.empty();
            for (TrackTestPiece p : TrackTestPiece.values()) {
                if (p.kind() == trackKind) return Optional.of(p.modelId());
            }
            return Optional.empty();
        }
    }

    /** An install, or the lang key (and its one argument) saying why there is none. */
    public record Result(Installed installed, String failKey, String failArg) {
        static Result ok(Installed installed) { return new Result(installed, null, null); }
        static Result fail(String key, String arg) { return new Result(null, key, arg); }
        public boolean ok() { return installed != null; }
    }

    private static final Map<String, Installed> LIVE = new ConcurrentHashMap<>();

    /** Where the stores' saves land while an install writes; null the rest of the time. */
    private static volatile Path writeOverride;

    private WorkbenchTestScratch() {}

    // ---- pure helpers (unit-tested) ----

    /** Hex digits of the digest kept in a scratch id: with the prefix, 27 of the 32 a name may have. */
    private static final int ID_HASH_CHARS = 20;

    /**
     * The reserved id a staged build tests under: the prefix and a truncated SHA-256 of the staged id,
     * so it is always a legal name and two staged ids never share one (a 32-bit {@code hashCode} could
     * collide, and the second install would then tear down the first mid-test).
     */
    public static String scratchIdFor(String stagedId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest((stagedId == null ? "" : stagedId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder hex = new StringBuilder(ID_HASH_CHARS);
        for (byte b : digest) {
            if (hex.length() >= ID_HASH_CHARS) break;
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return ID_PREFIX + hex.substring(0, ID_HASH_CHARS);
    }

    public static boolean isScratchId(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).startsWith(ID_PREFIX);
    }

    /**
     * The scene a build of {@code kind}/{@code subKind} is tested in, or null when its kind has none:
     * a part is only ever a piece of a carriage, a building has no test scene, and frames and the
     * official Lost City buildings are never staged.
     */
    public static Scene sceneFor(BuilderPhotoPaths.Kind kind, String subKind) {
        if (kind == null) return null;
        return switch (kind) {
            case CARRIAGE -> Scene.CARRIAGE;
            case CONTENTS -> Scene.CONTENTS;
            case CARRIAGE_GROUP -> Scene.WHOLE_GROUP;
            case PORTAL_ROOM -> Scene.PORTAL_ROOM;
            case TRACK -> {
                TrackKind track = TrackKind.fromId(subKind);
                if (track == TrackKind.PORTAL_ROOM) yield Scene.PORTAL_ROOM;
                boolean piece = false;
                for (TrackTestPiece p : TrackTestPiece.values()) piece |= p.kind() == track;
                yield track != null && piece ? Scene.TRACK : null;
            }
            case PART, BUILDING, CHUNK_FRAME, LOST_CITY -> null;
        };
    }

    /**
     * The kind a scratch install files its sidecars under. A dimensional room can arrive as the relay
     * kind {@code track/portal_room}; filed as a track it would get only its variants, so a room is
     * always filed as {@code PORTAL_ROOM}, whose roles include the copies and contents-allow files.
     */
    public static BuilderPhotoPaths.Kind sidecarKindFor(BuilderPhotoPaths.Kind kind, Scene scene) {
        return scene == Scene.PORTAL_ROOM ? BuilderPhotoPaths.Kind.PORTAL_ROOM : kind;
    }

    // ---- what the rest of the mod asks ----

    /** The stores' write target during an install — consulted by {@code PackageRegistry.activeWriteDir}. */
    public static Path writeOverride() {
        return writeOverride;
    }

    /** {@code <scratch>/<subSlug>} for every live scratch — prepended to {@code searchDirs}. */
    public static List<Path> liveDirs(String subSlug) {
        if (LIVE.isEmpty()) return List.of();
        List<Path> out = new ArrayList<>(LIVE.size());
        for (String id : LIVE.keySet()) out.add(dirFor(id).resolve(subSlug));
        return out;
    }

    /** The shell size a scratch carriage measured at, or null for any other id. */
    public static ContentsSize shellSizeOf(String id) {
        Installed in = live(id);
        return in != null && in.scene() == Scene.CARRIAGE ? in.size() : null;
    }

    /** The contents size a scratch contents measured at, or null for any other id. */
    public static ContentsSize contentsSizeOf(String id) {
        Installed in = live(id);
        return in != null && in.scene() == Scene.CONTENTS ? in.size() : null;
    }

    /** The mode tag a scratch room carried, or null. */
    public static String modeFor(String id) {
        Installed in = live(id);
        return in == null ? null : in.mode();
    }

    /** What chat should call a test's template: the build's own name for a scratch id. */
    public static String shownName(String id) {
        if (!isScratchId(id)) return id;
        for (Installed in : LIVE.values()) {
            if (id.contains(in.scratchId())) return in.buildName().isEmpty() ? in.stagedId() : in.buildName();
        }
        return id;
    }

    private static Installed live(String id) {
        return id == null ? null : LIVE.get(id.toLowerCase(Locale.ROOT));
    }

    private static Path root() {
        return PlayerDataPaths.dir(DIR);
    }

    private static Path dirFor(String scratchId) {
        return root().resolve(scratchId);
    }

    // ---- install / uninstall ----

    /** Stand {@code stagedId} up as a scratch template of its relay kind. Server thread. */
    public static synchronized Result install(ServerLevel level, String stagedId) {
        WorkbenchStagedBuild staged = WorkbenchStagingStore.find(stagedId).orElse(null);
        if (staged == null) return Result.fail("chat.dungeontrain.workbench.unknown", stagedId);
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(staged.relayKind());
        Scene scene = sceneFor(kind, staged.subKind());
        if (scene == null) {
            return Result.fail("chat.dungeontrain.workbench.test_no_scene",
                staged.relayKind().isEmpty() ? "?" : staged.relayKind());
        }
        Optional<CompoundTag> blocks = WorkbenchStagingStore.readBlocks(stagedId);
        if (blocks.isEmpty()) return Result.fail("chat.dungeontrain.workbench.failed", "no blocks for " + stagedId);
        StructureTemplate template;
        try {
            template = CarriageSnapshotTemplate.toTemplate(blocks.get(),
                level.registryAccess().lookupOrThrow(Registries.BLOCK));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Workbench test: '{}' would not convert: {}", stagedId, t.toString());
            return Result.fail("chat.dungeontrain.workbench.failed", t.toString());
        }

        CarriageDims dims = DungeonTrainWorldData.get(level.getServer().overworld()).dims();
        ContentsSize size = switch (scene) {
            case CARRIAGE -> shellSizeFor(template.getSize(), dims);
            case CONTENTS -> contentsSizeFor(template.getSize(), dims);
            default -> null;
        };
        if ((scene == Scene.CARRIAGE || scene == Scene.CONTENTS) && size == null) {
            Vec3i s = template.getSize();
            return Result.fail("chat.dungeontrain.workbench.test_wrong_size", s.getX() + "x" + s.getY() + "x" + s.getZ());
        }
        TrackKind trackKind = scene == Scene.PORTAL_ROOM ? TrackKind.PORTAL_ROOM
            : scene == Scene.TRACK ? TrackKind.fromId(staged.subKind()) : null;
        String scratchId = scratchIdFor(stagedId);
        int prefabsMissing = (int) staged.lootPrefabs().keySet().stream()
            .filter(id -> !LootPrefabStore.hasConfigFile(id) && !LootPrefabStore.hasBundled(id)).count();
        Installed installed = new Installed(stagedId, scratchId, staged.buildName(), scene, kind,
            staged.subKind(), trackKind, size, TemplateSidecars.modeOf(staged.sidecars()), prefabsMissing);

        // A re-press re-installs from the snapshot as it is now — the author may have saved since.
        if (LIVE.containsKey(scratchId)) uninstall(scratchId);
        // Live BEFORE writing: a store's next read finds the file through searchDirs.
        LIVE.put(scratchId, installed);
        writeOverride = dirFor(scratchId);
        try {
            write(installed, template);
            TemplateSidecars.applyFilesForTest(sidecarKindFor(kind, scene), staged.subKind(), scratchId,
                staged.sidecars());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Workbench test: could not stand '{}' up as {}: {}",
                stagedId, scene, e.toString());
            writeOverride = null;
            uninstall(scratchId);
            return Result.fail("chat.dungeontrain.workbench.failed", e.toString());
        } finally {
            writeOverride = null;
        }
        LOGGER.info("[DungeonTrain] Workbench test: '{}' stands as {} '{}' ({} missing loot prefab(s))",
            stagedId, scene, scratchId, prefabsMissing);
        return Result.ok(installed);
    }

    private static void write(Installed in, StructureTemplate template) throws IOException {
        String id = in.scratchId();
        switch (in.scene()) {
            case CARRIAGE -> {
                CarriageVariant.Custom variant = new CarriageVariant.Custom(id);
                CarriageTemplateStore.save(variant, template);
                CarriageVariantRegistry.register(variant);
            }
            case CONTENTS -> {
                CarriageContents.Custom contents = new CarriageContents.Custom(id);
                CarriageContentsStore.save(contents, template);
                CarriageContentsRegistry.register(contents);
            }
            case WHOLE_GROUP -> {
                CarriageGroup group = CarriageGroup.of(id);
                CarriageGroupTemplateStore.save(group, template);
                CarriageGroupRegistry.register(group);
            }
            case TRACK -> {
                TrackVariantStore.save(in.trackKind(), id, template);
                TrackVariantRegistry.register(in.trackKind(), id);
            }
            case PORTAL_ROOM -> {
                PortalRoomTemplateStore.save(id, template);
                TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, id);
            }
        }
    }

    /** Unregister {@code scratchId} and delete its folder. Safe to call for an id that is not live. */
    public static synchronized void uninstall(String scratchId) {
        Installed in = LIVE.remove(scratchId);
        if (in != null) {
            switch (in.scene()) {
                case CARRIAGE -> CarriageVariantRegistry.unregister(scratchId);
                case CONTENTS -> CarriageContentsRegistry.unregister(scratchId);
                case WHOLE_GROUP -> CarriageGroupRegistry.unregister(scratchId);
                case TRACK -> TrackVariantRegistry.unregister(in.trackKind(), scratchId);
                case PORTAL_ROOM -> {
                    TrackVariantRegistry.unregister(TrackKind.PORTAL_ROOM, scratchId);
                    PortalRoomSizes.forget(scratchId);
                }
            }
            LOGGER.info("[DungeonTrain] Workbench test: '{}' ({}) taken down", in.stagedId(), scratchId);
        }
        deleteTree(dirFor(scratchId));
    }

    /**
     * Take down every scratch no test names any more. Cheap when nothing is live — called each second
     * from the server tick.
     */
    public static void sweep() {
        if (LIVE.isEmpty()) return;
        Set<String> named = new HashSet<>();
        for (CarriageTestSession.Session s : CarriageTestSession.all()) named.add(s.templateId());
        for (var e : PortalTestSession.entries()) named.add(e.getValue().roomName());
        for (var e : PortalTestPending.entries()) named.add(e.getValue().roomName());
        for (String id : unused(LIVE.keySet(), named)) uninstall(id);
    }

    /**
     * The scratch ids in {@code live} that no test names. A name contains its scratch id rather than
     * equalling it: a piece of the line is filed as {@code <model>:<name>}.
     */
    static List<String> unused(java.util.Collection<String> live, java.util.Collection<String> named) {
        List<String> out = new ArrayList<>();
        for (String id : live) {
            boolean used = named.stream().anyMatch(n -> n != null && n.toLowerCase(Locale.ROOT).contains(id));
            if (!used) out.add(id);
        }
        return out;
    }

    /** Server start and stop: forget everything and delete the folder — a crash may have left one. */
    public static synchronized void wipe() {
        for (String id : List.copyOf(LIVE.keySet())) uninstall(id);
        deleteTree(root());
    }

    // ---- measuring ----

    /** The shell size whose box {@code size} is, or null for none. */
    static ContentsSize shellSizeFor(Vec3i size, CarriageDims dims) {
        int group = games.brennan.dungeontrain.config.DungeonTrainConfig.getGroupSize();
        for (ContentsSize s : ContentsSize.values()) {
            if (!s.available(dims, group)) continue;
            CarriageDims box = s.boxOrRoom(dims, group);
            if (size.equals(new Vec3i(box.length(), box.height(), box.width()))) return s;
        }
        return null;
    }

    /** The contents size whose interior {@code size} is, or null for none. */
    static ContentsSize contentsSizeFor(Vec3i size, CarriageDims dims) {
        int group = games.brennan.dungeontrain.config.DungeonTrainConfig.getGroupSize();
        for (ContentsSize s : ContentsSize.values()) {
            if (!s.available(dims, group)) continue;
            if (size.equals(CarriageContentsPlacer.interiorSize(s.boxOrRoom(dims, group)))) return s;
        }
        return null;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Workbench test: could not delete scratch folder {}: {}", dir, e.toString());
        }
    }
}
