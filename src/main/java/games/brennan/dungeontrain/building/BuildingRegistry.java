package games.brennan.dungeontrain.building;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Which building names exist — the shipped Lost City buildings first, then new buildings the jar ships,
 * then the player's own, then anything registered this session (a building entered before its first
 * save). The order is the editor's slot order, so it is rebuilt the same way every launch: every source
 * comes back sorted.
 */
public final class BuildingRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static LinkedHashSet<String> names;

    private BuildingRegistry() {}

    /** Rescan the jar and disk. */
    public static synchronized void reload() {
        names = new LinkedHashSet<>();
        names.addAll(new TreeSet<>(BuildingStore.shippedNames()));
        names.addAll(BuildingStore.bundledNewNames());
        for (String name : UserContentPaths.listBasenamesAcrossSearchDirs(Buildings.SUBDIR, ".nbt")) {
            if (Buildings.NAME.matcher(name).matches()) names.add(name);
        }
        LOGGER.info("[DungeonTrain] Building registry loaded — {} buildings", names.size());
    }

    /** Add {@code name}; false when it was already there or is not a valid name. */
    public static synchronized boolean register(String name) {
        if (names == null) reload();
        if (name == null || !Buildings.NAME.matcher(name).matches()) return false;
        return names.add(name);
    }

    /** Every building name, in slot order. */
    public static synchronized List<String> names() {
        if (names == null) reload();
        return List.copyOf(names);
    }

    /** True when {@code name} is a known building. */
    public static synchronized boolean contains(String name) {
        if (names == null) reload();
        return names.contains(name);
    }

    public static synchronized void clear() {
        names = null;
    }
}
