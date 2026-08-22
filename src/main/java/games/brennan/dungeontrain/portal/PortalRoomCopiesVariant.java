package games.brennan.dungeontrain.portal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The block variants an {@link PortalRoomCopies.Kind#SINGLE} room repeats — one cell's worth of
 * candidates for the floor plane and another for the roof, each with the weights, rotations and
 * block-entity payloads any other variant carries.
 *
 * <h2>Two planes, authored apart</h2>
 * <p>An Endless Open tile is a floor and a roof and nothing between them, and the two are not the
 * same surface: a plain of stone underfoot may want a sea-lantern sky over it, or no roof at all.
 * So this holds a palette per {@link Plane} rather than one for both. Setting only the floor is
 * still the ordinary case — see {@link #of(List)}, which gives both planes the same candidates,
 * which is what every room authored before the split has.</p>
 *
 * <h2>One block, which may be a variant</h2>
 * <p>The ordinary case is one block per plane, set from whatever the author is holding. When that
 * block should vary, the same Block Variant menu that authors every other cell in the game is opened
 * on it — see {@link PortalRoomCopiesPlot}, which presents one plane of this file as a one-cell plot
 * so the menu needs no special case for it. That is why a plane's value is a list rather than a
 * state: a variant <i>is</i> a candidate list, and one block is simply the one-entry case of it.</p>
 *
 * <h2>Why it is a sidecar and not part of the mode tag</h2>
 * <p>The tag is a capped {@code writeUtf} on two editor packets, and a candidate list carries NBT.
 * One block id already pushed the longest tag to about a hundred and thirty characters; a variant
 * would not fit at any cap worth having, and two of them less so. The tag says {@code single} and
 * names at most the one block both planes fall back to; the palettes live here, beside the room,
 * exactly as the room's own cells do.</p>
 *
 * <h2>One serialization, five users</h2>
 * <p>Reading goes through {@link CarriageVariantBlocks#parseCellValue} and writing through
 * {@link CarriageVariantBlocks#appendCellJson} — the pair the four block-variant sidecars already
 * share. A palette is one cell's worth of candidates with no lock id, which is the bare-array form
 * those two have always round-tripped, so every entry shape they learn this one gets too.</p>
 *
 * <h2>Air is a legitimate candidate</h2>
 * <p>An entry that resolves to air — the empty-placeholder sentinel, or a mob entry — leaves that
 * cell as a gap: a hole in the floor, an opening in the roof. That is an authored choice and the
 * only way to ask for one, so it is stored and stamped rather than filtered. A roof palette of
 * nothing but air roofs nothing at all, which is legal and is the author's business — and is how an
 * open sky over the plain is asked for.</p>
 *
 * <h2>Rolling</h2>
 * <p>{@link #resolve} is {@link CarriageVariantBlocks#pickIndexWeighted} and nothing else: the same
 * pure function of world seed, variant index and local position that the sidecars use. So a cell
 * walked back to is the cell you left, an Exact room's copies agree with each other, and a Dynamic
 * room's differ — all of it inherited rather than restated. The two planes roll independently
 * because they roll on their own cell's position, which is what already made floor and roof differ
 * within one palette.</p>
 */
public final class PortalRoomCopiesVariant {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Which of a tile's two planes a palette is for.
     *
     * <p>{@code ROOF} rather than {@code CEILING} because that is the word the editor row shows and
     * the command takes, and one word for one thing beats a translation layer between the two.</p>
     */
    public enum Plane {

        /** The plane a player walks on — {@code origin.getY()}. */
        FLOOR("floor", "Floor"),

        /** The plane over their head — {@code origin.getY() + size.getY() - 1}. */
        ROOF("roof", "Roof");

        private final String id;
        private final String displayName;

        Plane(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** The on-disk key / command-line token. */
        public String id() {
            return id;
        }

        /** Human-readable label for the editor row. */
        public String displayName() {
            return displayName;
        }

        /**
         * The plane named by {@code id}, or null when nothing answers to it.
         *
         * <p>Null and not a default, unlike the other parsers here: the callers are a command
         * argument and a plot key, and both would rather say "no such plane" than silently author
         * the floor when the author asked for the roof.</p>
         */
        public static Plane parse(String id) {
            if (id == null) return null;
            String key = id.trim().toLowerCase(Locale.ROOT);
            for (Plane p : values()) {
                if (p.id.equals(key)) return p;
            }
            return null;
        }
    }

    /**
     * Schema of the file this class writes.
     *
     * <p>v2 split the one {@code blocks} palette into {@code floor} and {@code roof}. A v1 file
     * reads as both planes at once, so nothing authored before the split changes what it stamps —
     * see {@link #parse}.</p>
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** The v1 key: one palette, used for both planes. Still read, never written. */
    private static final String LEGACY_BLOCKS_KEY = "blocks";

    /** Filename suffix, alongside {@code TrackKind.VARIANTS_EXT}'s {@code .variants.json}. */
    public static final String COPIES_EXT = ".copies.json";

    /**
     * Most candidates one plane's palette will hold.
     *
     * <p>A bound on the file rather than a judgement about variants, and deliberately the same
     * number {@code BlockVariantMenuController.MAX_ENTRIES} allows per cell — the menu is what
     * authors this, so a file it could not have written is a file that was hand-edited.</p>
     */
    public static final int MAX_ENTRIES = 32;

    /** Session cache keyed on room name. Invalidated on save. */
    private static final Map<String, PortalRoomCopiesVariant> CACHE = new HashMap<>();

    private final List<VariantState> floor;
    private final List<VariantState> roof;

    private PortalRoomCopiesVariant(List<VariantState> floor, List<VariantState> roof) {
        this.floor = List.copyOf(floor);
        this.roof = List.copyOf(roof);
    }

    /** The variant a room with no sidecar has: nothing to repeat, on either plane. */
    public static PortalRoomCopiesVariant empty() {
        return new PortalRoomCopiesVariant(List.of(), List.of());
    }

    /**
     * One palette for both planes — the shape every room had before the two were authored apart,
     * and still what a single held-block gesture means.
     */
    public static PortalRoomCopiesVariant of(List<VariantState> states) {
        return of(states, states);
    }

    /**
     * A variant holding each plane's {@code states} exactly as given, in order, capped at
     * {@link #MAX_ENTRIES}.
     *
     * <p><b>Nothing is filtered.</b> This used to drop entries that resolve to air — the
     * empty-placeholder sentinel and mob entries — to stop a floor plane growing a hole. That was
     * editing the author's data: a variant is a value they built in the Block Variant menu, and
     * storing something other than what they handed over makes the row's icon, a later Copy back
     * onto a cell, and the file on disk all disagree with what was added. Air in the variant is
     * also the only way to ask for <i>gaps</i> in the plain, so the filter removed the one thing it
     * was there to express. {@code PortalRoomSinglePlanes} honours air at stamp time instead.</p>
     *
     * <p>The cap is not a judgement about content: it is the same number
     * {@code BlockVariantMenuController.MAX_ENTRIES} allows per cell, so a variant that came from
     * the menu can never reach it and one that does came from a hand-edited file.</p>
     */
    public static PortalRoomCopiesVariant of(List<VariantState> floorStates, List<VariantState> roofStates) {
        List<VariantState> f = capped(floorStates);
        List<VariantState> r = capped(roofStates);
        return f.isEmpty() && r.isEmpty() ? new PortalRoomCopiesVariant(List.of(), List.of())
            : new PortalRoomCopiesVariant(f, r);
    }

    private static List<VariantState> capped(List<VariantState> states) {
        if (states == null || states.isEmpty()) return List.of();
        List<VariantState> kept = new ArrayList<>(Math.min(states.size(), MAX_ENTRIES));
        for (VariantState s : states) {
            if (kept.size() >= MAX_ENTRIES) break;
            if (s != null) kept.add(s);
        }
        return kept;
    }

    /** {@code plane}'s candidates, in the order they were added. Never null; possibly empty. */
    public List<VariantState> states(Plane plane) {
        return plane == Plane.ROOF ? roof : floor;
    }

    /** True when neither plane has anything to repeat. */
    public boolean isEmpty() {
        return floor.isEmpty() && roof.isEmpty();
    }

    /** True when {@code plane} has nothing to repeat — that plane is simply not written. */
    public boolean isEmpty(Plane plane) {
        return states(plane).isEmpty();
    }

    public int size(Plane plane) {
        return states(plane).size();
    }

    /**
     * The block ids of {@code plane}'s candidates, for the editor row's icons.
     *
     * <p>Ids and not states: the row draws an inventory icon, which is a property of the block, and
     * sending the whole state would put block-entity NBT on the wire for a picture. The row itself
     * shows only {@link #iconBlockId}; the rest is for the status line and for tests.</p>
     */
    public List<String> blockIds(Plane plane) {
        List<VariantState> states = states(plane);
        List<String> out = new ArrayList<>(states.size());
        for (VariantState s : states) {
            out.add(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
        }
        return Collections.unmodifiableList(out);
    }

    // ---------- mutation, by copy ----------

    /**
     * This variant with one plane's candidates replaced wholesale, the other left alone.
     *
     * <p>Replace and not append, because there are exactly two things that write here and both hand
     * over the whole list: the row's held-block gesture, which sets one block, and the Block Variant
     * menu through {@link PortalRoomCopiesPlot}, which owns add and remove itself. A third
     * append-shaped API would be a second way to author the same value.</p>
     */
    public PortalRoomCopiesVariant withStates(Plane plane, List<VariantState> newStates) {
        return plane == Plane.ROOF ? of(floor, newStates) : of(newStates, roof);
    }

    /** The one block the editor row for {@code plane} draws, or empty when nothing is authored. */
    public String iconBlockId(Plane plane) {
        List<String> ids = blockIds(plane);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    // ---------- rolling ----------

    /**
     * The candidate {@code plane}'s cell at {@code localPos} rolls, or null when that plane's
     * palette is empty.
     *
     * <p>{@code variantIndex} is the copy's identity — {@code PortalStructure.variantIndexFor} —
     * so an Exact room's copies all roll alike and a Dynamic room's do not, without this method
     * knowing which it is looking at.</p>
     */
    public VariantState resolve(Plane plane, BlockPos localPos, long worldSeed, int variantIndex) {
        List<VariantState> states = states(plane);
        if (states.isEmpty()) return null;
        return states.get(
            CarriageVariantBlocks.pickIndexWeighted(localPos, worldSeed, variantIndex, states));
    }

    // ---------- storage ----------

    public static Path configPathFor(String roomName) {
        return FMLPaths.CONFIGDIR.get()
            .resolve(TrackKind.PORTAL_ROOM.configSubdir())
            .resolve(roomName + COPIES_EXT);
    }

    /**
     * Source-tree path for {@code roomName}, or null when the tree is absent or read-only.
     * Mirrors {@code TrackVariantBlocks.sourcePathFor} so a dev-mode save lands beside the room.
     */
    public static Path sourcePathFor(String roomName) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(TrackKind.PORTAL_ROOM.sourceRelativePath())
            .resolve(roomName + COPIES_EXT);
    }

    /** Load {@code roomName}'s palettes — config first, then bundled, then empty. */
    public static synchronized PortalRoomCopiesVariant loadFor(String roomName) {
        PortalRoomCopiesVariant cached = CACHE.get(roomName);
        if (cached != null) return cached;
        PortalRoomCopiesVariant loaded = loadFromDisk(roomName);
        CACHE.put(roomName, loaded);
        return loaded;
    }

    private static PortalRoomCopiesVariant loadFromDisk(String roomName) {
        Path cfg = UserContentPaths.findFile(TrackKind.PORTAL_ROOM.subdir(), roomName + COPIES_EXT);
        if (cfg != null) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, roomName, "config " + cfg);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read copies variant {}: {}", cfg, e.toString());
            }
        }
        String resource = TrackKind.PORTAL_ROOM.bundledResourcePrefix() + roomName + COPIES_EXT;
        try (InputStream in = PortalRoomCopiesVariant.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, roomName, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled copies variant {}: {}",
                resource, e.toString());
            return empty();
        }
    }

    /**
     * Read a palette file. Total, like every other sidecar reader here: anything malformed logs and
     * reads as empty, which stamps the room normally rather than failing the pair's stamp.
     *
     * <p><b>v1 reads as both planes.</b> The old {@code blocks} key was one palette that the floor
     * and the roof were both written from, so reading it into both is not a migration but a
     * restatement of what the file already meant. It is only consulted when neither v2 key is
     * present, so a file that names one plane explicitly is never second-guessed.</p>
     */
    static PortalRoomCopiesVariant parse(Reader reader, String roomName, String origin) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Copies variant {} ({}) is not a JSON object — ignoring.",
                roomName, origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion") && obj.get("schemaVersion").getAsInt() > CURRENT_SCHEMA_VERSION) {
            LOGGER.warn("[DungeonTrain] Copies variant {} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
                roomName, origin, obj.get("schemaVersion").getAsInt(), CURRENT_SCHEMA_VERSION);
        }

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        boolean hasV2 = obj.has(Plane.FLOOR.id()) || obj.has(Plane.ROOF.id());
        if (hasV2) {
            return of(planeStates(obj, Plane.FLOOR.id(), blocks, roomName),
                planeStates(obj, Plane.ROOF.id(), blocks, roomName));
        }
        List<VariantState> legacy = planeStates(obj, LEGACY_BLOCKS_KEY, blocks, roomName);
        return of(legacy);
    }

    /** One key's candidates, or empty when the key is absent or unreadable. */
    private static List<VariantState> planeStates(
        JsonObject obj, String key, HolderLookup.RegistryLookup<Block> blocks, String roomName
    ) {
        if (!obj.has(key)) return List.of();
        // BlockPos.ZERO as the context position: a palette has no cell to belong to, and the
        // position is only ever used in the parser's warning text.
        CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
            obj.get(key), blocks, "copies variant " + roomName + " " + key, BlockPos.ZERO);
        return cell == null ? List.of() : cell.states();
    }

    /**
     * The variant a room actually repeats: its sidecar when it has one, and the single block named
     * in its {@code mode} tag when it does not.
     *
     * <p>The tag's {@code single:<blockid>} form is how Single first shipped, and
     * it is still what the tag carries for a room that has never been given a variant. The two
     * coexist by rank rather
     * than by migration: a room that has been given a real variant has a sidecar and uses it, and
     * every other room falls back to its tag. Nothing authored either way needs rewriting.</p>
     *
     * <p>The tag names one block, so it seeds both planes — a room that has never been opened in the
     * editor since the split still gets the floor and roof it always had.</p>
     */
    public static PortalRoomCopiesVariant forRoom(String roomName, PortalRoomCopies copies) {
        PortalRoomCopiesVariant sidecar = loadFor(roomName);
        if (!sidecar.isEmpty()) return sidecar;
        if (copies == null || !copies.repeatsOneBlock()) return empty();
        return PortalRoomSinglePlanes.stateFor(copies.blockId())
            .map(state -> of(List.of(new VariantState(state, null))))
            .orElseGet(PortalRoomCopiesVariant::empty);
    }

    /** Persist to the user config directory and refresh the session cache. */
    public synchronized void save(String roomName) throws IOException {
        Path file = configPathFor(roomName);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        CACHE.put(roomName, this);
        LOGGER.info("[DungeonTrain] Saved copies palettes for {} (floor {}, roof {}) to {}",
            roomName, floor.size(), roof.size(), file);
    }

    /**
     * Dev-mode write-through so the palettes ship with the next build. A variant with nothing on
     * either plane deletes the source file, so clearing the last candidate does not leave a stale
     * bundled resource behind.
     */
    public synchronized void saveToSource(String roomName) throws IOException {
        Path file = sourcePathFor(roomName);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (isEmpty()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled copies variant for {} (no entries)", roomName);
            return;
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
    }

    /**
     * The file text.
     *
     * <p>Both keys are always written, even when the two planes hold the same candidates. The
     * alternative — collapsing an unsplit variant back to the v1 {@code blocks} key — would make the
     * file's shape depend on its contents, so an author who set the roof and then set it back would
     * see the file change schema under them twice.</p>
     */
    String toJsonText() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"").append(Plane.FLOOR.id()).append("\": ");
        // lockId 0 — a palette is one list with no group semantics, which is the bare-array form.
        CarriageVariantBlocks.appendCellJson(sb, floor, 0);
        sb.append(",\n  \"").append(Plane.ROOF.id()).append("\": ");
        CarriageVariantBlocks.appendCellJson(sb, roof, 0);
        sb.append("\n}\n");
        return sb.toString();
    }

    public static synchronized void invalidate(String roomName) {
        CACHE.remove(roomName);
    }

    public static synchronized void invalidateAll() {
        CACHE.clear();
    }
}
