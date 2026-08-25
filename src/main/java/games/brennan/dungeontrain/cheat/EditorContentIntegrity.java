package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PendingCustomContentChoice;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.editor.PackageRegistry;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.world.CustomContentChoice;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dungeon Train is balanced against its own bundled carriages, contents, tracks,
 * tunnels and portal rooms. A world running with Train Editor content — the
 * player's own edits, or an imported dtpack — is not playing the game DT
 * balanced, so the whole server session runs in <b>Free Play</b> (see
 * {@link RunIntegrity}): stats and advancements don't persist to the
 * cross-world profile while that content is active.
 *
 * <p>This is the third source of the session Free Play taint, alongside
 * {@link AisDataIntegrity} (modified AIS config) and {@link CheatModIntegrity}
 * (known cheat mods), and it works the same way: {@link #isSessionFreePlay()}
 * is <em>derived</em>, never stored on the player. Removing or disabling the
 * content restores normal play — nothing has to be un-marked.</p>
 *
 * <p>Unlike the other two, this one comes with a choice: continue in Free Play,
 * or disable the content for this world. The answer is persisted per-world as a
 * {@link CustomContentChoice} on {@link DungeonTrainWorldData}, so it is asked
 * once per world rather than once per join.</p>
 *
 * <p><b>Asked before the world exists.</b> The normal path is
 * {@code CustomContentGate}, which puts the question up when the player presses
 * New World or reboards; the answer arrives here in {@code PendingCustomContentChoice}
 * and is committed in {@link #onOverworldLoad}, ahead of {@code prepareLevels}.
 * Asking at join instead — which is still the fallback, via
 * {@code ShowCustomContentPromptPacket}, for multiplayer and for worlds created
 * through the vanilla world list — meant the world had already generated from the
 * content and the player was already in Free Play by the time they were offered
 * the choice.</p>
 *
 * <p><b>Suppression</b> ({@link CustomContentChoice#DISABLE}) is enforced at a
 * single choke point: {@link UserContentPaths#searchDirs} returns nothing, so
 * every template store, sidecar and variant registry falls through to the
 * bundled classpath tier. The world choice is mirrored into a {@code volatile}
 * static here because {@link RunIntegrity#isCheated} is called on hot paths and
 * must not touch SavedData.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorContentIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Names of the enabled packages that actually contain files, computed at server start and
     * after every reload barrier. {@code null} = not yet computed (recomputed on next read);
     * empty = scanned and there is no custom content. Volatile: written on the server thread,
     * read from event handlers and the network thread.
     */
    private static volatile List<String> contentPackages = null;

    /**
     * The current world's answer, mirrored from {@link DungeonTrainWorldData} so the hot-path
     * Free Play gate and {@link UserContentPaths#searchDirs} can read it without a SavedData
     * lookup. Reset to {@link CustomContentChoice#UNSET} when no server is running.
     */
    private static volatile CustomContentChoice worldChoice = CustomContentChoice.UNSET;

    /**
     * Whether the currently-loaded template caches were built with content suppressed. Template
     * stores are mod-scoped statics, not world-scoped, so leaving a DISABLE world for an ALLOW one
     * inside the same game session would otherwise carry bundled-only templates into a world that
     * wants its custom ones. Tracked separately from {@link #worldChoice} for exactly that reason —
     * it describes the caches, not the world, and so it deliberately survives server stop.
     */
    private static volatile boolean suppressionInCaches = false;

    private EditorContentIntegrity() {}

    // ---- Read accessors ----

    /**
     * Does this install have any Train Editor content in an enabled package? True regardless of
     * whether the world has suppressed it — "the content exists" and "the content is loading"
     * are different questions, and the prompt / status command need the former.
     */
    public static boolean hasCustomContent() {
        return !contentPackageNames().isEmpty();
    }

    /**
     * Enabled packages that contain at least one file, in {@code searchDirs} order — shown to the
     * player in the prompt and the chat notice so they can see exactly WHAT is active. Empty when
     * the install is clean.
     */
    public static List<String> contentPackageNames() {
        List<String> cached = contentPackages;
        if (cached == null) {
            cached = scan();
            contentPackages = cached;
        }
        return cached;
    }

    /** Is custom content suppressed for the current world? */
    public static boolean isSuppressed() {
        return worldChoice.suppressesContent();
    }

    /** The current world's answer to the prompt. {@link CustomContentChoice#UNSET} = not asked yet. */
    public static CustomContentChoice choice() {
        return worldChoice;
    }

    /**
     * Is the current session Free Play because Train Editor content is active? True when custom
     * content exists AND this world hasn't disabled it. Note {@link CustomContentChoice#UNSET}
     * counts as active — the content is loading whether or not anyone has answered yet, so the
     * run is Free Play from the first tick rather than only after the player clicks Continue.
     */
    public static boolean isSessionFreePlay() {
        return !isSuppressed() && hasCustomContent();
    }

    // ---- Mutators ----

    /**
     * Record the player's answer for this world and make it take effect. Persists to
     * {@link DungeonTrainWorldData}, mirrors into the hot-path static, and — when the suppression
     * state actually flipped — runs the reload barrier so every template store re-queries the
     * (now different) search path.
     *
     * @return true when the suppression state changed, i.e. content was just turned on or off.
     */
    public static boolean setWorldChoice(MinecraftServer server, CustomContentChoice choice) {
        if (server == null || choice == null) return false;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return false;

        DungeonTrainWorldData.get(overworld).setCustomContentChoice(choice);
        worldChoice = choice;
        LOGGER.info("[DungeonTrain] Custom Train Editor content: world choice is now {}", choice);
        return syncCaches();
    }

    /**
     * Make the loaded template caches agree with the current suppression state, reloading them if
     * they don't. The search path changes shape when suppression flips, so every cached template,
     * sidecar and variant registry built under the old shape is stale — this is the same barrier
     * the package mutators use.
     *
     * @return true when a reload was actually needed.
     */
    private static synchronized boolean syncCaches() {
        boolean nowSuppressed = isSuppressed();
        if (suppressionInCaches == nowSuppressed) return false;
        suppressionInCaches = nowSuppressed;
        games.brennan.dungeontrain.template.TemplateStores.reloadCachesOnly();
        return true;
    }

    /**
     * Drop the cached scan so the next read re-walks the packages. Called from the reload barrier
     * ({@code TemplateStores.reloadAll}) because a player can author or import content mid-session.
     */
    public static void invalidate() {
        contentPackages = null;
    }

    // ---- Lifecycle ----

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Levels don't exist yet, so the world choice can't be read here — only the scan.
        worldChoice = CustomContentChoice.UNSET;
        invalidate();
        List<String> found = contentPackageNames();
        if (!found.isEmpty()) {
            LOGGER.info("[DungeonTrain] Train Editor content present in: {}", String.join(", ", found));
        }
    }

    /**
     * Mirror the saved choice as early as possible. {@link LevelEvent.Load} for the overworld
     * fires inside {@code MinecraftServer.createLevels}, <b>before</b> {@code prepareLevels}
     * generates the spawn region — so a world already set to DISABLE never stamps a single
     * carriage from custom templates. HIGH priority for the same reason
     * {@code WorldLifecycleEvents.onOverworldLoad} uses it.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        CustomContentChoice choice = data.customContentChoice();
        if (!choice.isAnswered() && PendingCustomContentChoice.isPresent()) {
            // Answered before this world existed — the player was asked when they pressed New World
            // or reboarded (see CustomContentGate), which is the whole reason that ask was moved
            // there. Committing it here, on the same event and before prepareLevels, is what makes
            // "run without my changes" mean it: the world never stamps a carriage from content it
            // declined, rather than declining after the fact.
            choice = PendingCustomContentChoice.get();
            data.setCustomContentChoice(choice);
            LOGGER.info("[DungeonTrain] Committed the pre-world custom content choice: {}", choice);
        }
        // Consumed either way. A world that already has an answer keeps it — its own decision
        // outranks one left over from a world creation that was abandoned — and a stale value must
        // not survive to reach the world after that.
        PendingCustomContentChoice.clear();

        worldChoice = choice;
        if (worldChoice.suppressesContent()) {
            LOGGER.info("[DungeonTrain] This world has custom Train Editor content disabled — "
                + "loading bundled content only.");
        }
        // Caches may have been built for the previous world in this game session.
        syncCaches();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        worldChoice = CustomContentChoice.UNSET;
        invalidate();
    }

    // ---- Internals ----

    /**
     * Walk every enabled package's working folder (plus the pre-migration {@code imported/} tier,
     * which {@link UserContentPaths#searchDirs} also honours) and report those holding at least
     * one regular file. A package folder that exists but is empty is not custom content — that's
     * what a fresh install's {@code user/} folder looks like.
     */
    private static List<String> scan() {
        List<String> found = new ArrayList<>();
        for (PackageInfo pkg : PackageRegistry.enabledPackages()) {
            if (containsAnyFile(pkg.workingDir())) found.add(pkg.name());
        }
        for (Path dir : UserContentPaths.importedPackageDirs()) {
            String name = dir.getFileName().toString();
            if (found.contains(name)) continue; // already counted via the registry
            if (containsAnyFile(dir)) found.add(name);
        }
        return List.copyOf(found);
    }

    /**
     * Any regular file anywhere beneath {@code dir}? Content lives one level down (per-kind
     * subfolders), so this walks rather than listing. An unreadable folder reports "no content" —
     * player-editable data must never take the game down, and the conservative answer here is the
     * one that leaves the run clean.
     */
    static boolean containsAnyFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonTrain] Couldn't scan {} for editor content: {}", dir, e.toString());
            return false;
        }
    }
}
