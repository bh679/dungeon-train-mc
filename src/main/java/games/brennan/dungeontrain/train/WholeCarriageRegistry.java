package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
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
 * Every whole carriage available to the Train Builder, alphabetical by id.
 *
 * <p>Flat and single-tier, unlike {@link CarriageContentsRegistry}: there are no built-ins to lead
 * with and no bundled manifest to reconcile against, because the mod ships no whole carriages. The
 * registry is exactly what is on disk under
 * {@link WholeCarriageTemplateStore#SUBDIR wholecarriages/} across the active package and every
 * enabled import — so a whole carriage that arrives in an imported package shows up in the picker
 * beside locally-authored ones with no extra step.</p>
 *
 * <p>Not a spawn pool. Whole carriages do not join the train generator yet; the shell/contents
 * split is still what {@code CarriagePlacer} rolls at spawn time. This registry exists so the
 * builder can list and reopen what it saved.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WholeCarriageRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sorted ids. Mutations go through register / unregister / reload. */
    private static final TreeSet<String> IDS = new TreeSet<>();

    private WholeCarriageRegistry() {}

    /** Every whole carriage, alphabetical. Snapshot — safe to iterate without locking. */
    public static synchronized List<WholeCarriage> all() {
        List<WholeCarriage> out = new ArrayList<>(IDS.size());
        for (String id : IDS) out.add(new WholeCarriage(id));
        return out;
    }

    /** Just the ids, alphabetical — what the builder's picker lists. */
    public static synchronized List<String> ids() {
        return List.copyOf(IDS);
    }

    public static synchronized Optional<WholeCarriage> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return IDS.contains(key) ? Optional.of(new WholeCarriage(key)) : Optional.empty();
    }

    /**
     * Register a whole carriage. Returns false when one is already registered under that id — which
     * is the normal case for a re-save, not an error. The caller writes the backing {@code .nbt}
     * first, so that a reload landing in between can never leave a registered id with no file.
     */
    public static synchronized boolean register(WholeCarriage wholeCarriage) {
        if (!WholeCarriage.isValidName(wholeCarriage.id())) {
            LOGGER.warn("[DungeonTrain] Ignoring whole carriage '{}' — invalid name", wholeCarriage.id());
            return false;
        }
        return IDS.add(wholeCarriage.id());
    }

    /** Remove an id. The caller is responsible for deleting the backing {@code .nbt}. */
    public static synchronized boolean unregister(String id) {
        return id != null && IDS.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Re-scan the active package plus every enabled import. */
    public static synchronized void reload() {
        IDS.clear();
        Set<String> basenames = UserContentPaths.listBasenamesAcrossSearchDirs(
            WholeCarriageTemplateStore.SUBDIR, ".nbt");
        int added = 0;
        for (String basename : basenames) {
            if (!WholeCarriage.isValidName(basename)) {
                LOGGER.warn("[DungeonTrain] Ignoring whole carriage '{}' — invalid name", basename);
                continue;
            }
            if (IDS.add(basename)) added++;
        }
        LOGGER.info("[DungeonTrain] Whole carriage registry: {} template(s)", added);
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

    /**
     * Unified {@link TemplateRegistry} surface. Every whole carriage is user-authored, so
     * {@link TemplateRegistry#builtins()} is always empty and {@link TemplateRegistry#customs()} is
     * the whole list.
     */
    private static final TemplateRegistry<Template.WholeCarriage> ADAPTER = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.WHOLE_CARRIAGE; }

        @Override
        public List<Template.WholeCarriage> all() {
            List<WholeCarriage> all = WholeCarriageRegistry.all();
            List<Template.WholeCarriage> out = new ArrayList<>(all.size());
            for (WholeCarriage w : all) out.add(new Template.WholeCarriage(w));
            return out;
        }

        @Override
        public List<Template.WholeCarriage> builtins() {
            return List.of();
        }

        @Override
        public List<Template.WholeCarriage> customs() {
            return all();
        }

        @Override
        public Optional<Template.WholeCarriage> find(String id) {
            return WholeCarriageRegistry.find(id).map(Template.WholeCarriage::new);
        }

        @Override public void reload() { WholeCarriageRegistry.reload(); }
        @Override public void clear() { WholeCarriageRegistry.clear(); }
    };

    public static TemplateRegistry<Template.WholeCarriage> adapter() { return ADAPTER; }
}
