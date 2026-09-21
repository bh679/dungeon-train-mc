package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
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
 * Every whole carriage known to the game — bundled ids first, then user-authored ones — alphabetical
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
public final class WholeCarriageRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TreeSet<String> BUILTINS = new TreeSet<>();
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    private WholeCarriageRegistry() {}

    /** Every whole carriage, bundled first then custom. Snapshot — safe to iterate without locking. */
    public static synchronized List<WholeCarriage> all() {
        List<WholeCarriage> out = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        for (String id : BUILTINS) out.add(new WholeCarriage(id));
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(new WholeCarriage(id));
        return out;
    }

    /** Just the ids, in {@link #all()} order. */
    public static synchronized List<String> ids() {
        List<String> out = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        out.addAll(BUILTINS);
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(id);
        return List.copyOf(out);
    }

    public static synchronized List<WholeCarriage> builtins() {
        List<WholeCarriage> out = new ArrayList<>(BUILTINS.size());
        for (String id : BUILTINS) out.add(new WholeCarriage(id));
        return out;
    }

    public static synchronized List<WholeCarriage> customs() {
        List<WholeCarriage> out = new ArrayList<>(CUSTOMS.size());
        for (String id : CUSTOMS) if (!BUILTINS.contains(id)) out.add(new WholeCarriage(id));
        return out;
    }

    /** True iff the mod jar ships {@code id}. */
    public static synchronized boolean isBundled(String id) {
        return id != null && BUILTINS.contains(id.toLowerCase(Locale.ROOT));
    }

    public static synchronized Optional<WholeCarriage> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return BUILTINS.contains(key) || CUSTOMS.contains(key) ? Optional.of(new WholeCarriage(key)) : Optional.empty();
    }

    /**
     * Register a user-authored whole carriage. False when already registered — the normal case for a
     * re-save. The caller writes the backing {@code .nbt} first.
     */
    public static synchronized boolean register(WholeCarriage item) {
        if (!WholeCarriage.isValidName(item.id())) {
            LOGGER.warn("[DungeonTrain] Ignoring whole carriage '{}' — invalid name", item.id());
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
        Set<String> bundled = BundledNbtScanner.scanBasenames(WholeCarriageRegistry.class, WholeCarriageTemplateStore.RESOURCE_PREFIX, LOGGER);
        for (String id : bundled) {
            if (WholeCarriage.isValidName(id)) BUILTINS.add(id);
            else LOGGER.warn("[DungeonTrain] Ignoring bundled whole carriage '{}' — invalid name", id);
        }
        Set<String> basenames = UserContentPaths.listBasenamesAcrossSearchDirs(WholeCarriageTemplateStore.SUBDIR, ".nbt");
        for (String basename : basenames) {
            if (WholeCarriage.isValidName(basename)) CUSTOMS.add(basename);
            else LOGGER.warn("[DungeonTrain] Ignoring whole carriage '{}' — invalid name", basename);
        }
        LOGGER.info("[DungeonTrain] WholeCarriage registry: {} bundled + {} user template(s)", BUILTINS.size(), CUSTOMS.size());
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

    private static final TemplateRegistry<Template.WholeCarriage> ADAPTER = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.WHOLE_CARRIAGE; }

        @Override
        public List<Template.WholeCarriage> all() {
            return wrap(WholeCarriageRegistry.all());
        }

        @Override
        public List<Template.WholeCarriage> builtins() {
            return wrap(WholeCarriageRegistry.builtins());
        }

        @Override
        public List<Template.WholeCarriage> customs() {
            return wrap(WholeCarriageRegistry.customs());
        }

        @Override
        public Optional<Template.WholeCarriage> find(String id) {
            return WholeCarriageRegistry.find(id).map(Template.WholeCarriage::new);
        }

        @Override public void reload() { WholeCarriageRegistry.reload(); }
        @Override public void clear() { WholeCarriageRegistry.clear(); }

        private List<Template.WholeCarriage> wrap(List<WholeCarriage> items) {
            List<Template.WholeCarriage> out = new ArrayList<>(items.size());
            for (WholeCarriage w : items) out.add(new Template.WholeCarriage(w));
            return out;
        }
    };

    public static TemplateRegistry<Template.WholeCarriage> adapter() { return ADAPTER; }
}
