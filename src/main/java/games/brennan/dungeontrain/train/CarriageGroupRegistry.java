package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every carriage group available to the Train Builder, alphabetical by id.
 *
 * <p>The sibling of {@link WholeCarriageRegistry} and flat for the same reason: the mod ships no
 * groups, so the registry is exactly what is on disk under
 * {@link CarriageGroupTemplateStore#SUBDIR carriagegroups/} across the active package and every
 * enabled import.</p>
 *
 * <p>Not a spawn pool. A group is authored, reopened and uploaded to its author's profile; nothing
 * places one on a train yet.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CarriageGroupRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sorted ids. Mutations go through register / unregister / reload. */
    private static final TreeSet<String> IDS = new TreeSet<>();

    private CarriageGroupRegistry() {}

    /** Every group, alphabetical. Snapshot — safe to iterate without locking. */
    public static synchronized List<CarriageGroup> all() {
        List<CarriageGroup> out = new ArrayList<>(IDS.size());
        for (String id : IDS) out.add(new CarriageGroup(id));
        return out;
    }

    /** Just the ids, alphabetical — what the builder's picker lists. */
    public static synchronized List<String> ids() {
        return List.copyOf(IDS);
    }

    public static synchronized Optional<CarriageGroup> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return IDS.contains(key) ? Optional.of(new CarriageGroup(key)) : Optional.empty();
    }

    /**
     * Register a group. False when one is already registered under that id — the normal case for a
     * re-save, not an error. The caller writes the backing {@code .nbt} first, so a reload landing in
     * between can never leave a registered id with no file.
     */
    public static synchronized boolean register(CarriageGroup group) {
        if (!CarriageGroup.isValidName(group.id())) {
            LOGGER.warn("[DungeonTrain] Ignoring carriage group '{}' — invalid name", group.id());
            return false;
        }
        return IDS.add(group.id());
    }

    /** Remove an id. The caller is responsible for deleting the backing {@code .nbt}. */
    public static synchronized boolean unregister(String id) {
        return id != null && IDS.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Re-scan the active package plus every enabled import. */
    public static synchronized void reload() {
        IDS.clear();
        Set<String> basenames = UserContentPaths.listBasenamesAcrossSearchDirs(
            CarriageGroupTemplateStore.SUBDIR, ".nbt");
        int added = 0;
        for (String basename : basenames) {
            if (!CarriageGroup.isValidName(basename)) {
                LOGGER.warn("[DungeonTrain] Ignoring carriage group '{}' — invalid name", basename);
                continue;
            }
            if (IDS.add(basename)) added++;
        }
        LOGGER.info("[DungeonTrain] Carriage group registry: {} template(s)", added);
    }

    public static synchronized void clear() {
        IDS.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
