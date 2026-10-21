package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No shipped loot pool sits on a Vista TV.
 *
 * <p>A TV's block entity is a one-slot container, so a pool authored on one rolled loot into the TV's
 * tuner slot: the TV never tuned, and the hidden item spilled when it broke. The TV rooms shipped 26 of
 * them. The roller now refuses a TV, so such a pool is dead data at best; this keeps it from coming back.</p>
 */
final class TvLootPoolsTest {

    private static final String TV = "vista:television";
    private static final String[] PREFIXES = {"track_portal_room_", "contents_", "carriage_", "whole_group_"};

    @Test
    @DisplayName("no container sidecar keys a pool on a television's position")
    void noPoolOnATelevision() throws IOException {
        Path data = RepoPaths.resources().resolve("data/dungeontrain");
        Map<String, List<Path>> templates = templatesByBasename(data);
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> sidecars = Files.list(data.resolve("containers"))) {
            for (Path sidecar : sidecars.filter(p -> p.toString().endsWith(".contents.json")).toList()) {
                Set<String> poolKeys = poolKeys(sidecar);
                if (poolKeys.isEmpty()) continue;
                for (Path template : templates.getOrDefault(baseName(sidecar), List.of())) {
                    checked++;
                    for (String pos : televisionPositions(template)) {
                        if (poolKeys.contains(pos)) offenders.add(sidecar.getFileName() + " @ " + pos);
                    }
                }
            }
        }
        assertTrue(checked > 0, "matched no sidecar to its template; the lookup is broken, not the data clean");
        assertTrue(offenders.isEmpty(), "loot pools authored on TVs: " + offenders);
    }

    private static String baseName(Path sidecar) {
        String name = sidecar.getFileName().toString().replace(".contents.json", "");
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return name;
    }

    private static Map<String, List<Path>> templatesByBasename(Path data) throws IOException {
        Map<String, List<Path>> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(data)) {
            files.filter(p -> p.toString().endsWith(".nbt")).forEach(p -> {
                String name = p.getFileName().toString();
                out.computeIfAbsent(name.substring(0, name.length() - 4), k -> new ArrayList<>()).add(p);
            });
        }
        return out;
    }

    private static Set<String> poolKeys(Path sidecar) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(sidecar)).getAsJsonObject();
        return root.has("pools") ? new HashSet<>(root.getAsJsonObject("pools").keySet()) : Set.of();
    }

    private static Set<String> televisionPositions(Path template) throws IOException {
        CompoundTag root = NbtIo.readCompressed(template, NbtAccounter.unlimitedHeap());
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        Set<Integer> tvStates = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            if (TV.equals(palette.getCompound(i).getString("Name"))) tvStates.add(i);
        }
        Set<String> out = new HashSet<>();
        if (tvStates.isEmpty()) return out;
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!tvStates.contains(block.getInt("state"))) continue;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            out.add(pos.getInt(0) + "," + pos.getInt(1) + "," + pos.getInt(2));
        }
        return out;
    }
}
