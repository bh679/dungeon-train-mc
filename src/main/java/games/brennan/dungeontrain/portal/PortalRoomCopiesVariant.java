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
import java.util.Map;

/**
 * The block variant an {@link PortalRoomCopies.Kind#SINGLE} room repeats — one cell's worth of
 * candidates, with the weights, rotations and block-entity payloads any other variant carries.
 *
 * <h2>One block, which may be a variant</h2>
 * <p>The ordinary case is one block, set from whatever the author is holding. When that block
 * should vary, the same Block Variant menu that authors every other cell in the game is opened on
 * it — see {@link PortalRoomCopiesPlot}, which presents this file as a one-cell plot so the menu
 * needs no special case for it. That is why the value is a list rather than a state: a variant
 * <i>is</i> a candidate list, and one block is simply the one-entry case of it.</p>
 *
 * <h2>Why it is a sidecar and not part of the mode tag</h2>
 * <p>The tag is a capped {@code writeUtf} on two editor packets, and a candidate list carries NBT.
 * One block id already pushed the longest tag to about a hundred and thirty characters; a variant
 * would not fit at any cap worth having. The tag says {@code single} and the palette lives here,
 * beside the room, exactly as the room's own cells do.</p>
 *
 * <h2>One serialization, five users</h2>
 * <p>Reading goes through {@link CarriageVariantBlocks#parseCellValue} and writing through
 * {@link CarriageVariantBlocks#appendCellJson} — the pair the four block-variant sidecars already
 * share. A palette is one cell's worth of candidates with no lock id, which is the bare-array form
 * those two have always round-tripped, so every entry shape they learn this one gets too.</p>
 *
 * <h2>Rolling</h2>
 * <p>{@link #resolve} is {@link CarriageVariantBlocks#pickIndexWeighted} and nothing else: the same
 * pure function of world seed, variant index and local position that the sidecars use. So a cell
 * walked back to is the cell you left, an Exact room's copies agree with each other, and a Dynamic
 * room's differ — all of it inherited rather than restated.</p>
 */
public final class PortalRoomCopiesVariant {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Schema of the file this class writes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Filename suffix, alongside {@code TrackKind.VARIANTS_EXT}'s {@code .variants.json}. */
    public static final String COPIES_EXT = ".copies.json";

    /**
     * Most candidates this file will hold.
     *
     * <p>A bound on the file rather than a judgement about variants, and deliberately the same
     * number {@code BlockVariantMenuController.MAX_ENTRIES} allows per cell — the menu is what
     * authors this, so a file it could not have written is a file that was hand-edited.</p>
     */
    public static final int MAX_ENTRIES = 32;

    /** Session cache keyed on room name. Invalidated on save. */
    private static final Map<String, PortalRoomCopiesVariant> CACHE = new HashMap<>();

    private final List<VariantState> states;

    private PortalRoomCopiesVariant(List<VariantState> states) {
        this.states = List.copyOf(states);
    }

    /** The variant a room with no sidecar has: nothing to repeat. */
    public static PortalRoomCopiesVariant empty() {
        return new PortalRoomCopiesVariant(List.of());
    }

    /**
     * A variant holding the usable entries of {@code states}, capped at {@link #MAX_ENTRIES}.
     *
     * <p><b>Mob and empty-placeholder entries are dropped.</b> Both resolve to air at stamp time,
     * and air in a floor plane is a hole in the plain that drops a player out of the world — the one
     * outcome this whole feature must never produce. A clipboard copied from a cell that mixes
     * blocks with a mob entry is still worth accepting; it just contributes its blocks.</p>
     */
    public static PortalRoomCopiesVariant of(List<VariantState> states) {
        if (states == null || states.isEmpty()) return empty();
        List<VariantState> usable = new ArrayList<>(Math.min(states.size(), MAX_ENTRIES));
        for (VariantState s : states) {
            if (usable.size() >= MAX_ENTRIES) break;
            if (s == null || s.isMob()) continue;
            if (CarriageVariantBlocks.isEmptyPlaceholder(s.state())) continue;
            usable.add(s);
        }
        return usable.isEmpty() ? empty() : new PortalRoomCopiesVariant(usable);
    }

    /** The candidates, in the order they were added. Never null; possibly empty. */
    public List<VariantState> states() {
        return states;
    }

    public boolean isEmpty() {
        return states.isEmpty();
    }

    public int size() {
        return states.size();
    }

    /**
     * The block ids of every candidate, for the editor row's icons.
     *
     * <p>Ids and not states: the row draws an inventory icon, which is a property of the block, and
     * sending the whole state would put block-entity NBT on the wire for a picture. The row itself
     * shows only {@link #iconBlockId}; the rest is for the status line and for tests.</p>
     */
    public List<String> blockIds() {
        List<String> out = new ArrayList<>(states.size());
        for (VariantState s : states) {
            out.add(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
        }
        return Collections.unmodifiableList(out);
    }

    // ---------- mutation, by copy ----------

    /**
     * This variant's candidates replaced wholesale.
     *
     * <p>Replace and not append, because there are exactly two things that write here and both hand
     * over the whole list: the row's held-block gesture, which sets one block, and the Block Variant
     * menu through {@link PortalRoomCopiesPlot}, which owns add and remove itself. A third
     * append-shaped API would be a second way to author the same value.</p>
     */
    public PortalRoomCopiesVariant withStates(List<VariantState> newStates) {
        return of(newStates);
    }

    /** The one block the editor row draws, or empty when nothing is authored. */
    public String iconBlockId() {
        List<String> ids = blockIds();
        return ids.isEmpty() ? "" : ids.get(0);
    }

    // ---------- rolling ----------

    /**
     * The candidate the cell at {@code localPos} rolls, or null when the palette is empty.
     *
     * <p>{@code variantIndex} is the copy's identity — {@code PortalStructure.variantIndexFor} —
     * so an Exact room's copies all roll alike and a Dynamic room's do not, without this method
     * knowing which it is looking at.</p>
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int variantIndex) {
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

    /** Load {@code roomName}'s palette — config first, then bundled, then empty. */
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
        if (!obj.has("blocks")) return empty();

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        // BlockPos.ZERO as the context position: a palette has no cell to belong to, and the
        // position is only ever used in the parser's warning text.
        CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
            obj.get("blocks"), blocks, "copies variant " + roomName, BlockPos.ZERO);
        if (cell == null) return empty();
        return of(cell.states());
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
        LOGGER.info("[DungeonTrain] Saved copies palette for {} ({} entries) to {}",
            roomName, states.size(), file);
    }

    /**
     * Dev-mode write-through so the palette ships with the next build. An empty palette deletes the
     * source file, so clearing the last candidate does not leave a stale bundled resource behind.
     */
    public synchronized void saveToSource(String roomName) throws IOException {
        Path file = sourcePathFor(roomName);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (states.isEmpty()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled copies variant for {} (no entries)", roomName);
            return;
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
    }

    String toJsonText() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"blocks\": ");
        // lockId 0 — a palette is one list with no group semantics, which is the bare-array form.
        CarriageVariantBlocks.appendCellJson(sb, states, 0);
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
