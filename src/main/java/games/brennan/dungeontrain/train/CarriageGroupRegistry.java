package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.util.BundledNbtScanner;
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
 * Every carriage group known to the game — bundled ids first, then user-authored ones — alphabetical
 * within each tier.
 *
 * <p>Two-tier like {@link CarriageContentsRegistry}: {@link #builtins()} is what the mod ships
 * under the store's bundled directory, {@link #customs()} is what is on disk across the active
 * package and every enabled import. An id in both is listed once, as a built-in — the user copy
 * shadows the bundled template at load time, exactly as it does for carriages.</p>
 *
 * <p>This is the pool the Whole section edits and the train generator picks from; the weights
 * live in {@link WholeWeights}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CarriageGroupRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TreeSet<String> BUILTINS = new TreeSet<>();
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    private CarriageGroupRegistry() {}

    /** Every carriage group, bundled first then custom. Snapshot — safe to iterate without locking. */
    public static synchronized List<CarriageGroup> all() {
        List<CarriageGroup> out = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        for (String id : BUILTINS) out.add(new CarriageGroup(id));
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(new CarriageGroup(id));
        return out;
    }

    /** Just the ids, in {@link #all()} order. */
    public static synchronized List<String> ids() {
        List<String> out = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        out.addAll(BUILTINS);
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(id);
        return List.copyOf(out);
    }

    public static synchronized List<CarriageGroup> builtins() {
        List<CarriageGroup> out = new ArrayList<>(BUILTINS.size());
        for (String id : BUILTINS) out.add(new CarriageGroup(id));
        return out;
    }

    public static synchronized List<CarriageGroup> customs() {
        List<CarriageGroup> out = new ArrayList<>(CUSTOMS.size());
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(new CarriageGroup(id));
        return out;
    }

    /** True iff the mod jar ships {@code id}. */
    public static synchronized boolean isBundled(String id) {
        return id != null && BUILTINS.contains(id.toLowerCase(Locale.ROOT));
    }

    public static synchronized Optional<CarriageGroup> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return BUILTINS.contains(key) || CUSTOMS.contains(key) ? Optional.of(new CarriageGroup(key)) : Optional.empty();
    }

    /**
     * Register a user-authored carriage group. False when already registered — the normal case for a
     * re-save. The caller writes the backing {@code .nbt} first.
     */
    public static synchronized boolean register(CarriageGroup item) {
        if (!CarriageGroup.isValidName(item.id())) {
            LOGGER.warn("[DungeonTrain] Ignoring carriage group '{}' — invalid name", item.id());
            return false;
        }
        return CUSTOMS.add(item.id());
    }

    /** Remove a user id. The caller deletes the backing {@code .nbt}. Built-ins cannot be removed. */
    public static synchronized boolean unregister(String id) {
        return id != null && CUSTOMS.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Re-scan the bundled tier, the active package and every enabled import. */
    public static synchronized void reload() {
        BUILTINS.clear();
        CUSTOMS.clear();
        Set<String> bundled = BundledNbtScanner.scanBasenames(CarriageGroupRegistry.class, CarriageGroupTemplateStore.RESOURCE_PREFIX, LOGGER);
        for (String id : bundled) {
            if (CarriageGroup.isValidName(id)) BUILTINS.add(id);
            else LOGGER.warn("[DungeonTrain] Ignoring bundled carriage group '{}' — invalid name", id);
        }
        Set<String> basenames = UserContentPaths.listBasenamesAcrossSearchDirs(CarriageGroupTemplateStore.SUBDIR, ".nbt");
        for (String basename : basenames) {
            if (CarriageGroup.isValidName(basename)) CUSTOMS.add(basename);
            else LOGGER.warn("[DungeonTrain] Ignoring carriage group '{}' — invalid name", basename);
        }
        LOGGER.info("[DungeonTrain] CarriageGroup registry: {} bundled + {} user template(s)", BUILTINS.size(), CUSTOMS.size());
    }

    public static synchronized void clear() {
        BUILTINS.clear();
        CUSTOMS.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    private static final TemplateRegistry<Template.CarriageGroup> ADAPTER = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.CARRIAGE_GROUP; }

        @Override
        public List<Template.CarriageGroup> all() {
            return wrap(CarriageGroupRegistry.all());
        }

        @Override
        public List<Template.CarriageGroup> builtins() {
            return wrap(CarriageGroupRegistry.builtins());
        }

        @Override
        public List<Template.CarriageGroup> customs() {
            return wrap(CarriageGroupRegistry.customs());
        }

        @Override
        public Optional<Template.CarriageGroup> find(String id) {
            return CarriageGroupRegistry.find(id).map(Template.CarriageGroup::new);
        }

        @Override public void reload() { CarriageGroupRegistry.reload(); }
        @Override public void clear() { CarriageGroupRegistry.clear(); }

        private List<Template.CarriageGroup> wrap(List<CarriageGroup> items) {
            List<Template.CarriageGroup> out = new ArrayList<>(items.size());
            for (CarriageGroup w : items) out.add(new Template.CarriageGroup(w));
            return out;
        }
    };

    public static TemplateRegistry<Template.CarriageGroup> adapter() { return ADAPTER; }
}
