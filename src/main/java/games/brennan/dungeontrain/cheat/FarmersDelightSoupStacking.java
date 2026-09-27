package games.brennan.dungeontrain.cheat;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Farmers' Delight's "stackable soups" override, held OFF for Dungeon Train runs.
 *
 * <p>Farmers' Delight ships {@code [overrides.stack_size] enableStackableSoupItems = true}, which
 * makes vanilla mushroom stew, beetroot soup and rabbit stew stack to 16. That changes how much food
 * a player can carry on the train, so a run with it on isn't the game every other run is measured
 * against. Three rules, matching how DT treats its own balance config:</p>
 *
 * <ol>
 *   <li><b>Installed ⇒ off.</b> The first boot with Farmers' Delight present writes the key to
 *       {@code false}, once ({@link #MARKER}). Our constructor runs before NeoForge loads any COMMON
 *       config, and Farmers' Delight only reads the key at boot (its
 *       {@code ModifyDefaultComponentsEvent} handler), so the very first session already plays with
 *       unstackable soups.</li>
 *   <li><b>Turned back on from a menu</b> (Configured, NeoForge's config screen) ⇒ the change is
 *       undone on the spot and the player is asked first — see
 *       {@code client.SoupStackingConfirmScreen}. Yes turns it on and the run is Free Play.</li>
 *   <li><b>Turned back on in the file</b> ⇒ no question: {@link DtConfigIntegrity} governs the key,
 *       so it is session Free Play at boot and permanent Free Play if edited mid-run.</li>
 * </ol>
 *
 * <p>No compile dependency on Farmers' Delight: only its modId, file name and key path. Everything
 * here fails open — a compat nicety must never stop the game starting.</p>
 */
public final class FarmersDelightSoupStacking {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "farmersdelight";

    /** Farmers' Delight's COMMON config (registered with the default {@code <modid>-common.toml} name). */
    public static final String FILE = "farmersdelight-common.toml";

    /** Dotted night-config path of the stackable-soups switch inside {@link #FILE}. */
    public static final String PATH = "overrides.stack_size.enableStackableSoupItems";

    /** Written once DT has switched soup stacking off, so a later deliberate choice is respected. */
    static final String MARKER = "farmersdelight_soup_stacking_off.marker";

    /** The value Farmers' Delight's config held at the last load/reload we saw. */
    private static volatile boolean lastKnown = false;

    /** The player confirmed "turn on anyway": the next reload showing {@code true} is theirs. */
    private static volatile boolean approved = false;

    /**
     * Farmers' Delight had no config file yet at our constructor, so the one-time switch-off waits
     * for NeoForge's first load of it (see {@link #applyDefaultOnce}).
     */
    private static volatile boolean pendingFirstLoad = false;

    /** Farmers' Delight's loaded COMMON config, captured from its events, for the confirmed write. */
    private static volatile ModConfig fdConfig = null;

    private FarmersDelightSoupStacking() {}

    /** Is Farmers' Delight loaded? False (never throws) when the mod list isn't available, e.g. unit tests. */
    public static boolean isInstalled() {
        try {
            ModList mods = ModList.get();
            return mods != null && mods.isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Called from DT's constructor. Switches soup stacking off once, then listens for Farmers'
     * Delight's config reloads. No-op without Farmers' Delight.
     */
    public static void init(Path configDir) {
        if (!isInstalled()) return;
        try {
            if (applyDefaultOnce(configDir)) {
                LOGGER.info("[DungeonTrain] Farmers' Delight found — stackable soups switched off ({} in {})",
                    PATH, FILE);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not switch off Farmers' Delight stackable soups — leaving {} as is", FILE, t);
        }
        try {
            registerReloadListener();
        } catch (Throwable t) {
            // Rule 3 still holds without this: DtConfigIntegrity's sweep catches the change. Only the
            // "ask first" step is lost, so a menu change goes straight to Free Play.
            LOGGER.warn("[DungeonTrain] Could not listen for Farmers' Delight config changes — menu changes will not prompt", t);
        }
    }

    /**
     * Write {@link #PATH}{@code = false} into {@code configDir/FILE} unless {@link #MARKER} says it
     * was already done; then write the marker. Returns whether it wrote. Package-visible for tests.
     *
     * <p>A <b>missing</b> file is left alone: a file holding only this key is one NeoForge calls
     * "not correct", and correcting it leaves a stray {@code farmersdelight-common-1.toml.bak} behind
     * on every fresh install. Instead the switch-off waits for NeoForge's first load of the file it
     * generates ({@link #onConfigEvent}) — COMMON configs load before registry events, so that is
     * still before Farmers' Delight reads the key.</p>
     */
    static boolean applyDefaultOnce(Path configDir) throws IOException {
        Path marker = markerPath(configDir);
        if (Files.exists(marker)) return false;
        if (!Files.exists(configDir.resolve(FILE))) {
            pendingFirstLoad = true;
            return false;
        }
        writeFlag(configDir.resolve(FILE), false);
        writeMarker(marker);
        return true;
    }

    /** Whether the switch-off is waiting for NeoForge to generate the file. Package-visible for tests. */
    static boolean isPendingFirstLoad() {
        return pendingFirstLoad;
    }

    private static Path markerPath(Path configDir) {
        return configDir.resolve(DungeonTrain.MOD_ID).resolve(MARKER);
    }

    private static void writeMarker(Path marker) throws IOException {
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "Dungeon Train switched off Farmers' Delight stackable soups once. "
            + "Delete this file to have it do so again on next launch.\n");
    }

    /** First load of a file NeoForge just generated: switch soups off through the loaded config. */
    private static void applyOnFirstLoad(ModConfig config) throws IOException {
        pendingFirstLoad = false;
        setLoaded(config, false);
        writeMarker(markerPath(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()));
        LOGGER.info("[DungeonTrain] Farmers' Delight config generated — stackable soups switched off ({} in {})",
            PATH, FILE);
    }

    /**
     * Set the stackable-soups key in a Farmers' Delight config file, keeping every other setting and
     * comment. A missing file is created holding just this key — NeoForge fills in the rest when it
     * loads the file.
     */
    static void writeFlag(Path file, boolean value) {
        // sync(): night-config 3.8 writes asynchronously by default, and the caller (our constructor,
        // ConfigReset) needs the value on disk before NeoForge — or the player — reads the file.
        try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder().sync().build()) {
            if (Files.exists(file)) config.load();
            config.set(PATH, value);
            config.save();
        }
    }

    /** The key's value in {@code file}, or empty when the file/key is absent or not a boolean. */
    static Optional<Boolean> readFlag(Path file) {
        if (!Files.exists(file)) return Optional.empty();
        try (CommentedFileConfig config = CommentedFileConfig.of(file)) {
            config.load();
            return config.get(PATH) instanceof Boolean b ? Optional.of(b) : Optional.empty();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not read {} ({})", file, t.toString());
            return Optional.empty();
        }
    }

    private static void registerReloadListener() {
        Optional<? extends ModContainer> container = ModList.get().getModContainerById(MOD_ID);
        if (container.isEmpty() || container.get().getEventBus() == null) {
            LOGGER.warn("[DungeonTrain] Farmers' Delight has no event bus — menu changes to stackable soups will not prompt");
            return;
        }
        container.get().getEventBus().addListener(FarmersDelightSoupStacking::onConfigEvent);
    }

    private static void onConfigEvent(ModConfigEvent event) {
        ModConfig config = event.getConfig();
        if (config == null || !FILE.equals(config.getFileName())) return;
        try {
            fdConfig = config;
            boolean current = currentValue(config);
            if (event instanceof ModConfigEvent.Reloading) {
                onReloaded(config, current);
            } else if (event instanceof ModConfigEvent.Loading) {
                if (pendingFirstLoad) {
                    applyOnFirstLoad(config);
                    current = false;
                }
                lastKnown = current;
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Farmers' Delight config event handling failed", t);
        }
    }

    /**
     * How a menu change is told apart from a hand edit: <b>which thread the reload arrives on</b>.
     * Every in-game editor (Configured, NeoForge's own config screen) saves through
     * {@code ILoadedConfig.save()}, which writes the file and posts {@code Reloading} right there on
     * the client thread. A change made to the file on disk only reaches the game through NeoForge's
     * file watcher, which reloads and posts {@code Reloading} on night-config's watcher thread. So
     * this doesn't depend on what screen happens to be open.
     */
    private static void onReloaded(ModConfig config, boolean current) {
        boolean inGameSave = FMLEnvironment.dist.isClient()
            && games.brennan.dungeontrain.client.SoupStackingClientHooks.isClientThread();
        if (!shouldAsk(lastKnown, current, approved, inGameSave)) {
            lastKnown = current;
            if (!current) approved = false; // switched off again: the next switch-on asks afresh
            return;
        }
        // Undo first, so the file never holds a change the player hasn't confirmed — that is what
        // DtConfigIntegrity's sweep reads. The save re-fires Reloading with false (and the watcher
        // re-reads the file as false shortly after), both no-ops here.
        setLoaded(config, false);
        LOGGER.info("[DungeonTrain] Stackable soups switched on from a menu — undone pending confirmation");
        games.brennan.dungeontrain.client.SoupStackingClientHooks.askToEnable();
    }

    /**
     * Pure: should this reload be undone and the player asked first? Only a switch from off to on,
     * that the player hasn't already confirmed, saved from inside the game. A switch-on picked up by
     * the file watcher is a hand edit (so is anything on a dedicated server), which goes straight to
     * Free Play. Package-visible for tests.
     */
    static boolean shouldAsk(boolean previous, boolean current, boolean alreadyApproved, boolean inGameSave) {
        return current && !previous && !alreadyApproved && inGameSave;
    }

    /**
     * The player chose "turn on anyway". Writes {@code true} through Farmers' Delight's loaded
     * config; the reload that follows is accepted, and {@link DtConfigIntegrity} marks the run Free
     * Play (right away when a world is running, at the next world start otherwise).
     */
    public static void enableAfterConfirmation() {
        approved = true;
        ModConfig config = fdConfig;
        try {
            if (config != null && config.getLoadedConfig() != null) {
                setLoaded(config, true);
            } else {
                writeFlag(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(FILE), true);
            }
            lastKnown = true;
            LOGGER.info("[DungeonTrain] Stackable soups switched on after confirmation — runs are Free Play");
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not switch on Farmers' Delight stackable soups", t);
        }
        DtConfigIntegrity.onConfigReloaded();
    }

    private static boolean currentValue(ModConfig config) {
        IConfigSpec.ILoadedConfig loaded = config.getLoadedConfig();
        if (loaded == null) return lastKnown;
        return loaded.config().get(PATH) instanceof Boolean b && b;
    }

    private static void setLoaded(ModConfig config, boolean value) {
        IConfigSpec.ILoadedConfig loaded = config.getLoadedConfig();
        loaded.config().set(PATH, value);
        loaded.save();
    }
}
