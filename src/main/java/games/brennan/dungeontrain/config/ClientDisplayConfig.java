package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

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
    public static final ModConfigSpec.BooleanValue OPENED_ADVANCEMENTS_BEFORE;
    public static final ModConfigSpec.BooleanValue RIDE_SNAPSHOTS_ENABLED;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MAX_STORED;
    public static final ModConfigSpec.BooleanValue RIDE_SNAPSHOT_CHAT_LOG;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MIN_FPS;
    public static final ModConfigSpec.IntValue RIDE_SNAPSHOT_MIN_TPS;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(ClientDisplayConfig::build);
        SPEC = pair.getRight();
        ALL_SCALE = pair.getLeft().allScale;
        WORLDSPACE_CHANNEL = pair.getLeft().worldspaceChannel;
        HUD_CHANNEL = pair.getLeft().hudChannel;
        DEVELOPER_POPUP_SHOWN_BEFORE = pair.getLeft().developerPopupShownBefore;
        DEVELOPER_POPUP_OPTED_OUT = pair.getLeft().developerPopupOptedOut;
        OPENED_ADVANCEMENTS_BEFORE = pair.getLeft().openedAdvancementsBefore;
        RIDE_SNAPSHOTS_ENABLED = pair.getLeft().rideSnapshotsEnabled;
        RIDE_SNAPSHOT_INTERVAL_SECONDS = pair.getLeft().rideSnapshotIntervalSeconds;
        RIDE_SNAPSHOT_MAX_STORED = pair.getLeft().rideSnapshotMaxStored;
        RIDE_SNAPSHOT_CHAT_LOG = pair.getLeft().rideSnapshotChatLog;
        RIDE_SNAPSHOT_MIN_FPS = pair.getLeft().rideSnapshotMinFps;
        RIDE_SNAPSHOT_MIN_TPS = pair.getLeft().rideSnapshotMinTps;
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
                .comment("Maximum ride photos kept in memory per run (oldest dropped first). Each is a small off-screen texture; higher = more variety behind the death pages, slightly more VRAM.")
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
        b.pop();

        return new Holder(allScale, worldspaceChannel, hudChannel, developerPopupShownBefore, developerPopupOptedOut, openedAdvancementsBefore,
                rideSnapshotsEnabled, rideSnapshotIntervalSeconds, rideSnapshotMaxStored, rideSnapshotChatLog,
                rideSnapshotMinFps, rideSnapshotMinTps);
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

    private record Holder(
            ModConfigSpec.DoubleValue allScale,
            ModConfigSpec.DoubleValue worldspaceChannel,
            ModConfigSpec.DoubleValue hudChannel,
            ModConfigSpec.BooleanValue developerPopupShownBefore,
            ModConfigSpec.BooleanValue developerPopupOptedOut,
            ModConfigSpec.BooleanValue openedAdvancementsBefore,
            ModConfigSpec.BooleanValue rideSnapshotsEnabled,
            ModConfigSpec.IntValue rideSnapshotIntervalSeconds,
            ModConfigSpec.IntValue rideSnapshotMaxStored,
            ModConfigSpec.BooleanValue rideSnapshotChatLog,
            ModConfigSpec.IntValue rideSnapshotMinFps,
            ModConfigSpec.IntValue rideSnapshotMinTps
    ) {}
}
