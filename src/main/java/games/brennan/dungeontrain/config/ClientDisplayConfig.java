package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.client.FramerateThrottle;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-scoped Forge config for purely cosmetic per-player display
 * preferences. Persists at {@code <minecraft>/config/dungeontrain-client.toml}
 * and is registered alongside {@link DungeonTrainConfig} from
 * {@link games.brennan.dungeontrain.DungeonTrain}.
 *
 * <p>Held separately from {@code DungeonTrainConfig} because that config is
 * {@code ModConfig.Type.SERVER} (per-world, server-loaded) — display scale is
 * a client-only visual choice that should follow the player across worlds.</p>
 *
 * <p>Three independent stored values:
 *   <ul>
 *     <li>{@code allScale} — master multiplier applied on top of every other
 *         display channel. Lets the player nudge everything together without
 *         touching the per-channel sliders.</li>
 *     <li>{@code worldspaceChannel} — base scale for world-space (3D) UI:
 *         X menu, editor menus, and debug labels.</li>
 *     <li>{@code hudChannel} — base scale for 2D HUD overlays (top-left
 *         version line, top-centre editor status bar).</li>
 *   </ul>
 *   Renderers consume the *effective* scales {@link #getWorldspaceScale()}
 *   and {@link #getHudScale()} which already fold {@code allScale} in.
 *   The Options menu reads/writes the raw stored values via the
 *   {@code Channel}/{@code AllScale} accessors so its three sliders behave
 *   independently — adjusting HUD does not move the All slider.</p>
 */
public final class ClientDisplayConfig {

    public static final double MIN_SCALE = 0.2;
    public static final double MAX_SCALE = 2.0;
    /** Master multiplier default — {@code 1.0} means "leave per-channel values untouched". */
    public static final double DEFAULT_ALL_SCALE = 1.0;
    /** Worldspace channel default — {@code 0.7} ships a slightly compact in-world UI; the slider lets players tune from there. */
    public static final double DEFAULT_WORLDSPACE_CHANNEL = 0.7;
    /** HUD channel default — {@code 0.4} ships a compact HUD; the slider lets players bump up if 0.4 reads too tight at their GUI scale. */
    public static final double DEFAULT_HUD_CHANNEL = 0.4;
    /** Step applied per click of the menu's {@code [-]} / {@code [+]} buttons. */
    public static final double STEP = 0.10;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue ALL_SCALE;
    public static final ModConfigSpec.DoubleValue WORLDSPACE_CHANNEL;
    public static final ModConfigSpec.DoubleValue HUD_CHANNEL;
    public static final ModConfigSpec.BooleanValue DEVELOPER_POPUP_SHOWN_BEFORE;
    public static final ModConfigSpec.BooleanValue DEVELOPER_POPUP_OPTED_OUT;
    public static final ModConfigSpec.BooleanValue FREE_PLAY_CONFIRM_OPTED_OUT;
    public static final ModConfigSpec.BooleanValue DEV_CONSENT_GRANTED;
    public static final ModConfigSpec.DoubleValue DEV_CONSENT_GRANT_SESSION;
    public static final ModConfigSpec.DoubleValue DEV_CONSENT_LAST_MSG_TO_DEV;
    public static final ModConfigSpec.BooleanValue OPENED_ADVANCEMENTS_BEFORE;
    public static final ModConfigSpec.BooleanValue RIDE_SNAPSHOTS_ENABLED;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MAX_STORED;
    public static final ModConfigSpec.BooleanValue RIDE_SNAPSHOT_CHAT_LOG;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MIN_FPS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MIN_TPS;
    public static final ModConfigSpec.BooleanValue RIDE_SNAPSHOT_DISK_OFFLOAD;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_FLUSH_MIN_FPS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_FLUSH_MIN_TPS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MAX_ON_DISK;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MAX_RESOLUTION;
    public static final ModConfigSpec.BooleanValue FRAMERATE_THROTTLE_ENABLED;
    public static final ModConfigSpec.IntValue FRAMERATE_THROTTLE_FPS;
    public static final ModConfigSpec.BooleanValue DELETE_WORLD_ON_REBOARD;
    /**
     * Relay pool ids of community (player-written) books this player has read, stored as decimal strings.
     * GLOBAL client-side read history — persists across worlds and servers (unlike the retired per-world
     * server set), so "read once = read everywhere" survives even when the relay can't personalise the
     * pool (older relay, no network consent, or offline). See {@link games.brennan.dungeontrain.event.SharedBookReadMirror}.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHARED_BOOKS_READ;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(ClientDisplayConfig::build);
        SPEC = pair.getRight();
        ALL_SCALE = pair.getLeft().allScale;
        WORLDSPACE_CHANNEL = pair.getLeft().worldspaceChannel;
        HUD_CHANNEL = pair.getLeft().hudChannel;
        DEVELOPER_POPUP_SHOWN_BEFORE = pair.getLeft().developerPopupShownBefore;
        DEVELOPER_POPUP_OPTED_OUT = pair.getLeft().developerPopupOptedOut;
        FREE_PLAY_CONFIRM_OPTED_OUT = pair.getLeft().freePlayConfirmOptedOut;
        DEV_CONSENT_GRANTED = pair.getLeft().devConsentGranted;
        DEV_CONSENT_GRANT_SESSION = pair.getLeft().devConsentGrantSession;
        DEV_CONSENT_LAST_MSG_TO_DEV = pair.getLeft().devConsentLastMsgToDev;
        OPENED_ADVANCEMENTS_BEFORE = pair.getLeft().openedAdvancementsBefore;
        RIDE_SNAPSHOTS_ENABLED = pair.getLeft().rideSnapshotsEnabled;
        RIDE_SNAPSHOT_INTERVAL_SECONDS = pair.getLeft().rideSnapshotIntervalSeconds;
        RIDE_SNAPSHOT_MAX_STORED = pair.getLeft().rideSnapshotMaxStored;
        RIDE_SNAPSHOT_CHAT_LOG = pair.getLeft().rideSnapshotChatLog;
        RIDE_SNAPSHOT_MIN_FPS = pair.getLeft().rideSnapshotMinFps;
        RIDE_SNAPSHOT_MIN_TPS = pair.getLeft().rideSnapshotMinTps;
        RIDE_SNAPSHOT_DISK_OFFLOAD = pair.getLeft().rideSnapshotDiskOffload;
        RIDE_SNAPSHOT_FLUSH_MIN_FPS = pair.getLeft().rideSnapshotFlushMinFps;
        RIDE_SNAPSHOT_FLUSH_MIN_TPS = pair.getLeft().rideSnapshotFlushMinTps;
        RIDE_SNAPSHOT_MAX_ON_DISK = pair.getLeft().rideSnapshotMaxOnDisk;
        RIDE_SNAPSHOT_MAX_RESOLUTION = pair.getLeft().rideSnapshotMaxResolution;
        FRAMERATE_THROTTLE_ENABLED = pair.getLeft().framerateThrottleEnabled;
        FRAMERATE_THROTTLE_FPS = pair.getLeft().framerateThrottleFps;
        DELETE_WORLD_ON_REBOARD = pair.getLeft().deleteWorldOnReboard;
        SHARED_BOOKS_READ = pair.getLeft().sharedBooksRead;
    }

    private ClientDisplayConfig() {}

    private static Holder build(ModConfigSpec.Builder b) {
        b.push("display");
        ModConfigSpec.DoubleValue allScale = b
                .comment("Master multiplier applied on top of both per-channel scales. 1.0 = no master tweak; bump up/down to scale every display together while keeping the per-channel sliders' relative offsets.")
                .defineInRange("allScale", DEFAULT_ALL_SCALE, MIN_SCALE, MAX_SCALE);
        ModConfigSpec.DoubleValue worldspaceChannel = b
                .comment("Base scale for world-space (3D in-world) UI and labels — X menu, editor menus, debug text. Effective scale = worldspaceChannel * allScale.")
                .defineInRange("worldspaceChannel", DEFAULT_WORLDSPACE_CHANNEL, MIN_SCALE, MAX_SCALE);
        ModConfigSpec.DoubleValue hudChannel = b
                .comment("Base scale for 2D HUD overlays (top-left version line, editor status bar). Effective scale = hudChannel * allScale.")
                .defineInRange("hudChannel", DEFAULT_HUD_CHANNEL, MIN_SCALE, MAX_SCALE);
        b.pop();

        b.push("developerPopup");
        ModConfigSpec.BooleanValue developerPopupShownBefore = b
                .comment("Whether the developer welcome popup has been surfaced at least once on this install. Used to gate the \"Don't ask again\" button — it only appears on returning showings, not the first time.")
                .define("shownBefore", false);
        ModConfigSpec.BooleanValue developerPopupOptedOut = b
                .comment("Whether the player has clicked \"Don't ask again\" on the developer welcome popup. When true, the popup is permanently suppressed regardless of play/quit cycles. Reset by manually editing this file back to false.")
                .define("optedOut", false);
        b.pop();

        b.push("freePlayConfirm");
        ModConfigSpec.BooleanValue freePlayConfirmOptedOut = b
                .comment("Whether the player has ticked \"Don't show this again\" on the Free Play confirmation. When true, switching to creative/spectator or running a cheat command starts Free Play immediately, with no confirm screen. Reset by editing this back to false.")
                .define("optedOut", false);
        b.pop();

        b.push("devMessageConsent");
        ModConfigSpec.BooleanValue devConsentGranted = b
                .comment("Whether the player has ever accepted a Developer message (typed @Dev to a consent prompt). Together with the session/timestamp below this governs whether relayed Developer messages appear in in-game chat. Persisted so consent can survive a world reload. Managed automatically — not meant to be edited by hand.")
                .define("granted", false);
        ModConfigSpec.DoubleValue devConsentGrantSession = b
                .comment("Internal: the server session token in which consent was last granted (millis). Managed automatically.")
                .defineInRange("grantSessionMillis", 0.0, 0.0, Double.MAX_VALUE);
        ModConfigSpec.DoubleValue devConsentLastMsgToDev = b
                .comment("Internal: wall-clock millis of the player's last message to the dev (in-game chat after consent, or a menu-chat send). Anchors the 20-minute consent window. Managed automatically.")
                .defineInRange("lastMessageToDevMillis", 0.0, 0.0, Double.MAX_VALUE);
        b.pop();

        b.push("advancementsHint");
        ModConfigSpec.BooleanValue openedAdvancementsBefore = b
                .comment("Whether the player has ever opened the advancements screen on this install. While false, earning a Dungeon Train gameplay advancement shows a one-line chat hint reminding the player of the (rebindable) key that opens advancements. Flips to true the first time the advancements screen is closed, permanently silencing the hint. Reset this to false to see the hint again.")
                .define("openedBefore", false);
        b.pop();

        b.push("rideSnapshots");
        ModConfigSpec.BooleanValue rideSnapshotsEnabled = b
                .comment("Take third-person photos throughout your ride and show them as the death-screen backgrounds. Set false to disable both capture and the backdrops entirely.")
                .define("enabled", true);
        ModConfigSpec.IntValue rideSnapshotIntervalSeconds = b
                .comment("Baseline seconds between scenic ride photos. Context shots (nearby combat, weapon/tool changes, reading a narrative book) are taken on top of this on their own cooldowns.")
                .defineInRange("intervalSeconds", 30, 5, 120);
        ModConfigSpec.IntValue rideSnapshotMaxStored = b
                .comment("Maximum ride photos kept in memory (unflushed) per run before they are offloaded to disk (oldest in-memory dropped first when no disk-offload window is available). Each is a small off-screen texture; higher = more variety behind the death pages, slightly more VRAM.")
                .defineInRange("maxStored", 12, 4, 32);
        ModConfigSpec.BooleanValue rideSnapshotChatLog = b
                .comment("Log each ride photo to chat ([Ride Snapshot] TAG - reason) as it is taken. Toggle in-game via the X menu -> Options. Off by default.")
                .define("chatLog", false);
        ModConfigSpec.IntValue rideSnapshotMinFps = b
                .comment("Skip ride photos while client FPS is below this. Taking a photo adds a brief GPU read-back hitch, so it is only spent when the game has headroom; below this the shot is skipped and retried (every 20s) once FPS recovers. 0 = never skip on FPS.")
                .defineInRange("minFps", 30, 0, 240);
        ModConfigSpec.IntValue rideSnapshotMinTps = b
                .comment("Skip ride photos while server TPS is below this. Single-player only - in multiplayer the client can't read the server's tick rate, so only the FPS gate applies there. 0 = never skip on TPS.")
                .defineInRange("minTps", 18, 0, 20);
        ModConfigSpec.BooleanValue rideSnapshotDiskOffload = b
                .comment("Offload ride photos to disk to free memory during a run. When FPS/TPS have headroom (see flushMinFps / flushMinTps) AND a menu is open, in-memory photos are written to <gamedir>/dungeontrain/ride-snapshots/*.png and their GPU textures released; the death screen loads them back from disk. Per-run and deleted on world join/leave. false = keep every photo in memory for the whole run (never touch disk).")
                .define("diskOffloadEnabled", true);
        ModConfigSpec.IntValue rideSnapshotFlushMinFps = b
                .comment("Only offload photos to disk while client FPS is at or above this. The PNG encode is a brief main-thread cost, so it is spent only when the game has headroom - set this higher than minFps. 0 = no FPS requirement to flush.")
                .defineInRange("flushMinFps", 50, 0, 240);
        ModConfigSpec.IntValue rideSnapshotFlushMinTps = b
                .comment("Only offload photos to disk while (single-player) server TPS is at or above this. Multiplayer can't read the server's tick rate, so only the FPS gate applies there. Set at or above minTps. 0 = no TPS requirement to flush.")
                .defineInRange("flushMinTps", 19, 0, 20);
        ModConfigSpec.IntValue rideSnapshotMaxOnDisk = b
                .comment("Maximum ride photos retained per run across memory + disk (oldest dropped first). Each is a small (<=640px) PNG, so a full run is only a few MB; set above maxStored so offloading keeps more variety than memory alone. Clamped to at least maxStored.")
                .defineInRange("maxOnDisk", 64, 8, 256);
        ModConfigSpec.IntValue rideSnapshotMaxResolution = b
                .comment("Ceiling (long-edge pixels) for ride-photo capture. 0 = AUTO: the standard 1080, rising to 1440/2160 only when Distant Horizons is active together with shaders or Fabulous graphics (picked by frame rate). A positive value CAPS that result — e.g. 1440 clamps to <=1440, 1080 disables the higher-resolution tiers entirely. It never raises resolution above what the auto logic and your real window size already allow. Values below 1080 will shrink normal photos too. Also settable in-game via the X menu -> Options -> Snapshot Max Resolution.")
                .defineInRange("maxResolution", 0, 0, 4320);
        b.pop();

        b.push("framerateThrottle");
        ModConfigSpec.BooleanValue framerateThrottleEnabled = b
                .comment("Cap the render framerate while the game is paused, or while its window is unfocused or minimised. Minecraft 1.21.1 does not throttle rendering behind the pause screen (and has no AFK limiter — that arrived in 1.21.2), so an idle game keeps re-rendering an unchanging frame at full speed, spinning up fans for nothing. Set false to render idle frames at full speed.")
                .define("enabled", true);
        ModConfigSpec.IntValue framerateThrottleFps = b
                .comment("Framerate to cap to while paused / unfocused / minimised. This can only ever LOWER your framerate — if your Max Framerate video setting is already below this, that lower value is kept. Lower saves more power; the pause menu still feels responsive well below 30.")
                .defineInRange("fps", FramerateThrottle.DEFAULT_THROTTLE_FPS,
                        FramerateThrottle.MIN_THROTTLE_FPS, FramerateThrottle.MAX_THROTTLE_FPS);
        b.pop();

        b.push("world");
        ModConfigSpec.BooleanValue deleteWorldOnReboard = b
                .comment("Delete the old world's save folder when reboarding (creating a fresh world) from the death screen. Dungeon Train is designed around a new world per run, so this defaults on to keep the world list and disk clean. Only auto-generated \"<prefix> <timestamp>\" saves (Dungeon Train / Dev World / World) are ever deleted — renamed or hand-made worlds and editor worlds are always kept. Toggleable in-game via the trash icon next to the reboard button.")
                .define("deleteOnReboard", true);
        b.pop();

        b.push("sharedBooks");
        ModConfigSpec.ConfigValue<List<? extends String>> sharedBooksRead = b
                .comment("Relay pool ids (as strings) of community player-written books you've read. GLOBAL read",
                         "history that follows you across worlds and servers, so a book read in one world stays",
                         "read in a brand-new one even when the server can't personalise your loot. Managed",
                         "automatically — you can clear it by emptying this list.")
                .defineListAllowEmpty("read", () -> List.<String>of(), () -> "0",
                        o -> o instanceof String);
        b.pop();

        return new Holder(allScale, worldspaceChannel, hudChannel, developerPopupShownBefore, developerPopupOptedOut, freePlayConfirmOptedOut,
                devConsentGranted, devConsentGrantSession, devConsentLastMsgToDev, openedAdvancementsBefore,
                rideSnapshotsEnabled, rideSnapshotIntervalSeconds, rideSnapshotMaxStored, rideSnapshotChatLog,
                rideSnapshotMinFps, rideSnapshotMinTps,
                rideSnapshotDiskOffload, rideSnapshotFlushMinFps, rideSnapshotFlushMinTps, rideSnapshotMaxOnDisk,
                rideSnapshotMaxResolution,
                framerateThrottleEnabled, framerateThrottleFps, deleteWorldOnReboard, sharedBooksRead);
    }

    /**
     * Client config loads early in the client lifecycle but isn't guaranteed
     * to be ready before the first frame draws (e.g. title-screen overlays
     * created during mod construction). Callers must guard reads through
     * the getters below; direct {@code SPEC.isLoaded()} use is fine for
     * write paths that should silently no-op pre-load.
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    // ----- Effective getters: used by HUD overlays and world-space renderers.
    // Both fold the master multiplier in so callers don't have to. -----

    /** Effective scale for world-space UI: {@code worldspaceChannel * allScale}. */
    public static double getWorldspaceScale() {
        return getWorldspaceChannel() * getAllScale();
    }

    /** Effective scale for 2D HUD overlays: {@code hudChannel * allScale}. */
    public static double getHudScale() {
        return getHudChannel() * getAllScale();
    }

    // ----- Raw channel accessors: used by the Options menu so each slider
    // shows / mutates its own stored value without cross-channel coupling. -----

    public static double getAllScale() {
        return isLoaded() ? snapToTenth(ALL_SCALE.get()) : DEFAULT_ALL_SCALE;
    }

    public static double getWorldspaceChannel() {
        return isLoaded() ? snapToTenth(WORLDSPACE_CHANNEL.get()) : DEFAULT_WORLDSPACE_CHANNEL;
    }

    public static double getHudChannel() {
        return isLoaded() ? snapToTenth(HUD_CHANNEL.get()) : DEFAULT_HUD_CHANNEL;
    }

    public static void setAllScale(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        ALL_SCALE.set(snapped);
        ALL_SCALE.save();
    }

    public static void setWorldspaceChannel(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        WORLDSPACE_CHANNEL.set(snapped);
        WORLDSPACE_CHANNEL.save();
    }

    public static void setHudChannel(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(clamp(value));
        HUD_CHANNEL.set(snapped);
        HUD_CHANNEL.save();
    }

    private static double clamp(double value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /**
     * Round to the nearest tenth so values are always multiples of {@link #STEP}
     * (0.1) — no 0.05/0.15/0.25/… lurking from float drift after repeated steps
     * or from a stale {@code dungeontrain-client.toml} written under an older
     * config schema. Read-side snapping ensures the menu and renderers never
     * see the in-between state; write-side snapping cleans up the file.
     */
    private static double snapToTenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // ----- Developer welcome popup state -----

    /** Has the popup been shown at least once on this install? */
    public static boolean isDeveloperPopupShownBefore() {
        return isLoaded() && DEVELOPER_POPUP_SHOWN_BEFORE.get();
    }

    public static void setDeveloperPopupShownBefore(boolean value) {
        if (!isLoaded()) return;
        DEVELOPER_POPUP_SHOWN_BEFORE.set(value);
        DEVELOPER_POPUP_SHOWN_BEFORE.save();
    }

    /** Has the player permanently opted out of the popup? */
    public static boolean isDeveloperPopupOptedOut() {
        return isLoaded() && DEVELOPER_POPUP_OPTED_OUT.get();
    }

    public static void setDeveloperPopupOptedOut(boolean value) {
        if (!isLoaded()) return;
        DEVELOPER_POPUP_OPTED_OUT.set(value);
        DEVELOPER_POPUP_OPTED_OUT.save();
    }

    // ----- Free Play confirmation opt-out -----

    /** Has the player ticked "Don't show this again" on the Free Play confirmation? */
    public static boolean isFreePlayConfirmOptedOut() {
        return isLoaded() && FREE_PLAY_CONFIRM_OPTED_OUT.get();
    }

    public static void setFreePlayConfirmOptedOut(boolean value) {
        if (!isLoaded()) return;
        FREE_PLAY_CONFIRM_OPTED_OUT.set(value);
        FREE_PLAY_CONFIRM_OPTED_OUT.save();
    }

    // ----- Developer-message consent state (see DevMessageConsentClient) -----

    /** Has the player ever accepted a Developer message (typed @Dev to a consent prompt)? */
    public static boolean isDevConsentGranted() {
        return isLoaded() && DEV_CONSENT_GRANTED.get();
    }

    /** Server session token (millis) consent was last granted in; {@code 0.0} if never. */
    public static double getDevConsentGrantSession() {
        return isLoaded() ? DEV_CONSENT_GRANT_SESSION.get() : 0.0;
    }

    /** Wall-clock millis of the player's last message to the dev; {@code 0.0} if never. */
    public static double getDevConsentLastMsgToDev() {
        return isLoaded() ? DEV_CONSENT_LAST_MSG_TO_DEV.get() : 0.0;
    }

    /** Persist the whole consent triple in one write (single {@code .save()}). No-op pre-load. */
    public static void setDevConsentState(boolean granted, double grantSession, double lastMsgToDevMs) {
        if (!isLoaded()) return;
        DEV_CONSENT_GRANTED.set(granted);
        DEV_CONSENT_GRANT_SESSION.set(grantSession);
        DEV_CONSENT_LAST_MSG_TO_DEV.set(lastMsgToDevMs);
        DEV_CONSENT_GRANTED.save();
    }

    // ----- Advancements keybind hint state -----

    /**
     * Has the player ever opened the advancements screen on this install? While
     * {@code false}, earning a Dungeon Train gameplay advancement surfaces a
     * one-line chat hint pointing at the (rebindable) advancements keybind.
     */
    public static boolean isOpenedAdvancementsBefore() {
        return isLoaded() && OPENED_ADVANCEMENTS_BEFORE.get();
    }

    /**
     * Persist the "opened advancements" flag. Idempotent: skips the
     * {@code .save()} (a TOML write) when the value is unchanged, because this
     * is called on every advancements-screen close, not just the first.
     */
    public static void setOpenedAdvancementsBefore(boolean value) {
        if (!isLoaded()) return;
        if (OPENED_ADVANCEMENTS_BEFORE.get() == value) return;
        OPENED_ADVANCEMENTS_BEFORE.set(value);
        OPENED_ADVANCEMENTS_BEFORE.save();
    }

    // ----- Ride snapshots (third-person photos used as death-screen backgrounds) -----

    /** Capture + death-screen backdrops on? Defaults to {@code true} (also pre-load). */
    public static boolean isRideSnapshotsEnabled() {
        return !isLoaded() || RIDE_SNAPSHOTS_ENABLED.get();
    }

    /** Baseline seconds between scenic ride photos. */
    public static int getRideSnapshotIntervalSeconds() {
        return isLoaded() ? RIDE_SNAPSHOT_INTERVAL_SECONDS.get() : 30;
    }

    /** Max photos held in memory per run (oldest evicted first). */
    public static int getRideSnapshotMaxStored() {
        return isLoaded() ? RIDE_SNAPSHOT_MAX_STORED.get() : 12;
    }

    /** Log each ride photo to chat as it's taken? Toggled from the X menu → Options. */
    public static boolean isRideSnapshotChatLogEnabled() {
        return isLoaded() && RIDE_SNAPSHOT_CHAT_LOG.get();
    }

    public static void setRideSnapshotChatLog(boolean value) {
        if (!isLoaded()) return;
        RIDE_SNAPSHOT_CHAT_LOG.set(value);
        RIDE_SNAPSHOT_CHAT_LOG.save();
    }

    /** Minimum client FPS required to take a ride photo; {@code 0} disables the FPS gate. */
    public static int getRideSnapshotMinFps() {
        return isLoaded() ? RIDE_SNAPSHOT_MIN_FPS.get() : 30;
    }

    /** Minimum (single-player) server TPS required to take a ride photo; {@code 0} disables the TPS gate. */
    public static int getRideSnapshotMinTps() {
        return isLoaded() ? RIDE_SNAPSHOT_MIN_TPS.get() : 18;
    }

    /**
     * Offload ride photos to disk to free memory during a run? Deliberately {@code false} until
     * the config is loaded — unlike {@link #isRideSnapshotsEnabled()} this must NOT default-on
     * pre-load, so nothing writes to disk before the retention caps are known.
     */
    public static boolean isRideSnapshotDiskOffloadEnabled() {
        return isLoaded() && RIDE_SNAPSHOT_DISK_OFFLOAD.get();
    }

    /** Minimum client FPS required to offload a photo to disk ("high" perf); {@code 0} disables the FPS gate. */
    public static int getRideSnapshotFlushMinFps() {
        return isLoaded() ? RIDE_SNAPSHOT_FLUSH_MIN_FPS.get() : 50;
    }

    /** Minimum (single-player) server TPS required to offload a photo to disk; {@code 0} disables the TPS gate. */
    public static int getRideSnapshotFlushMinTps() {
        return isLoaded() ? RIDE_SNAPSHOT_FLUSH_MIN_TPS.get() : 19;
    }

    /** Max photos retained per run across memory + disk; never less than the in-memory cap {@link #getRideSnapshotMaxStored()}. */
    public static int getRideSnapshotMaxOnDisk() {
        int floor = getRideSnapshotMaxStored();
        return Math.max(floor, isLoaded() ? RIDE_SNAPSHOT_MAX_ON_DISK.get() : 64);
    }

    /**
     * Ceiling (long-edge px) for ride-photo capture; {@code 0} = AUTO (the adaptive
     * DH+shaders/Fabulous logic in {@code RideSnapshotCapture}). A positive value caps the chosen edge.
     * Defaults to {@code 0} (AUTO) pre-load so behaviour matches the shipped adaptive default.
     */
    public static int getRideSnapshotMaxResolution() {
        return isLoaded() ? RIDE_SNAPSHOT_MAX_RESOLUTION.get() : 0;
    }

    /** Set the ride-photo resolution ceiling ({@code 0} = AUTO). Toggled from the X menu → Options; no-op pre-load. */
    public static void setRideSnapshotMaxResolution(int value) {
        if (!isLoaded()) return;
        RIDE_SNAPSHOT_MAX_RESOLUTION.set(value);
        RIDE_SNAPSHOT_MAX_RESOLUTION.save();
    }

    // ----- Idle framerate throttle (paused / unfocused / minimised) -----

    /**
     * Throttle the render framerate while idle? Defaults to {@code true}, but deliberately
     * {@code false} pre-load: {@link #isLoaded()} is already checked by the mixin, and defaulting
     * off here means a config that never loads simply leaves vanilla's behaviour alone.
     */
    public static boolean isFramerateThrottleEnabled() {
        return isLoaded() && FRAMERATE_THROTTLE_ENABLED.get();
    }

    /**
     * Persist the idle-throttle toggle. Idempotent: skips the {@code .save()} (a TOML write) when
     * the value is unchanged. Driven by {@code /framerate-throttle on|off}.
     */
    public static void setFramerateThrottleEnabled(boolean value) {
        if (!isLoaded()) return;
        if (FRAMERATE_THROTTLE_ENABLED.get() == value) return;
        FRAMERATE_THROTTLE_ENABLED.set(value);
        FRAMERATE_THROTTLE_ENABLED.save();
    }

    /** Framerate to cap to while idle. Only ever lowers the rate — see {@link FramerateThrottle#decide}. */
    public static int getFramerateThrottleFps() {
        return isLoaded() ? FRAMERATE_THROTTLE_FPS.get() : FramerateThrottle.DEFAULT_THROTTLE_FPS;
    }

    /** Persist the idle-throttle cap. Clamped to {@link FramerateThrottle}'s configurable range. */
    public static void setFramerateThrottleFps(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(FramerateThrottle.MIN_THROTTLE_FPS,
                Math.min(FramerateThrottle.MAX_THROTTLE_FPS, value));
        if (FRAMERATE_THROTTLE_FPS.get() == clamped) return;
        FRAMERATE_THROTTLE_FPS.set(clamped);
        FRAMERATE_THROTTLE_FPS.save();
    }

    // ----- Delete old world on reboard (death-screen trash toggle) -----

    /**
     * Delete the old world's save when reboarding? Defaults to {@code true} (also pre-load) —
     * Dungeon Train is a new-world-per-run game, so abandoned run saves are cleaned up unless
     * the player opts out via the death screen's trash toggle. The delete path itself carries
     * a second guard: only auto-generated {@code "Dungeon Train <timestamp>"} saves are removed.
     */
    public static boolean isDeleteWorldOnReboard() {
        return !isLoaded() || DELETE_WORLD_ON_REBOARD.get();
    }

    /**
     * Persist the reboard-delete toggle. Idempotent: skips the {@code .save()} (a TOML write)
     * when the value is unchanged. Driven by the death screen's trash chip.
     */
    public static void setDeleteWorldOnReboard(boolean value) {
        if (!isLoaded()) return;
        if (DELETE_WORLD_ON_REBOARD.get() == value) return;
        DELETE_WORLD_ON_REBOARD.set(value);
        DELETE_WORLD_ON_REBOARD.save();
    }

    // ----- Global client-side community-book read history (see SharedBookReadSyncClient / SharedBookReadMirror) -----

    /**
     * The relay pool ids of community books this player has read, parsed from the persisted string list.
     * Empty (never null) before the config loads or when nothing has been read. Non-numeric or non-positive
     * stored entries are skipped defensively (a hand-edited file can't crash the read path).
     */
    public static Set<Integer> readSharedIds() {
        Set<Integer> out = new LinkedHashSet<>();
        if (!isLoaded()) return out;
        for (String s : SHARED_BOOKS_READ.get()) {
            if (s == null) continue;
            try {
                int id = Integer.parseInt(s.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {
                // hand-edited / stale entry — skip, don't crash
            }
        }
        return out;
    }

    /**
     * Record that this player has read the community book with relay pool {@code id}, persisting it to
     * {@code dungeontrain-client.toml}. Idempotent — skips the TOML write when the id is already present.
     * No-op pre-load or for a non-positive id. Returns {@code true} when it was newly added.
     */
    public static boolean markSharedRead(int id) {
        if (!isLoaded() || id <= 0) return false;
        Set<Integer> ids = readSharedIds();
        if (!ids.add(id)) return false; // already recorded — no write
        List<String> stored = new ArrayList<>(ids.size());
        for (int v : ids) stored.add(Integer.toString(v));
        SHARED_BOOKS_READ.set(stored);
        SHARED_BOOKS_READ.save();
        return true;
    }

    private record Holder(
            ModConfigSpec.DoubleValue allScale,
            ModConfigSpec.DoubleValue worldspaceChannel,
            ModConfigSpec.DoubleValue hudChannel,
            ModConfigSpec.BooleanValue developerPopupShownBefore,
            ModConfigSpec.BooleanValue developerPopupOptedOut,
            ModConfigSpec.BooleanValue freePlayConfirmOptedOut,
            ModConfigSpec.BooleanValue devConsentGranted,
            ModConfigSpec.DoubleValue devConsentGrantSession,
            ModConfigSpec.DoubleValue devConsentLastMsgToDev,
            ModConfigSpec.BooleanValue openedAdvancementsBefore,
            ModConfigSpec.BooleanValue rideSnapshotsEnabled,
            ModConfigSpec.IntValue rideSnapshotIntervalSeconds,
            ModConfigSpec.IntValue rideSnapshotMaxStored,
            ModConfigSpec.BooleanValue rideSnapshotChatLog,
            ModConfigSpec.IntValue rideSnapshotMinFps,
            ModConfigSpec.IntValue rideSnapshotMinTps,
            ModConfigSpec.BooleanValue rideSnapshotDiskOffload,
            ModConfigSpec.IntValue rideSnapshotFlushMinFps,
            ModConfigSpec.IntValue rideSnapshotFlushMinTps,
            ModConfigSpec.IntValue rideSnapshotMaxOnDisk,
            ModConfigSpec.IntValue rideSnapshotMaxResolution,
            ModConfigSpec.BooleanValue framerateThrottleEnabled,
            ModConfigSpec.IntValue framerateThrottleFps,
            ModConfigSpec.BooleanValue deleteWorldOnReboard,
            ModConfigSpec.ConfigValue<List<? extends String>> sharedBooksRead
    ) {}
}
