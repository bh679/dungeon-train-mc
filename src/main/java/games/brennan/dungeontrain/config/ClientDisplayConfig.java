package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.client.BookAuthorChatSyncClient;
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

    // ----- Editor world-space menu render distance -----

    /**
     * Default cap on how far the editor's world-space menus draw, in blocks.
     *
     * <p>A hundred and twenty-eight is generous on purpose — half the range, and past the point
     * where a panel is readable anyway — so the setting ships as a backstop against the far end of
     * a big build area rather than as something you immediately have to loosen. Auto's own
     * in-template rule is far tighter (see {@link #AUTO_TEMPLATE_DISTANCE_BLOCKS}); the smaller of
     * the two applies.</p>
     */
    public static final int DEFAULT_MENU_RENDER_DISTANCE = 128;
    /** Floor for the setting — below this the panel for the plot you are standing in starts to vanish. */
    public static final int MIN_MENU_RENDER_DISTANCE = 5;
    /** Ceiling — past a couple of hundred blocks the panels are unreadable anyway, so this is "no limit". */
    public static final int MAX_MENU_RENDER_DISTANCE = 256;
    /** Blocks per click of the Menu Distance row's {@code −} / {@code +} cells. */
    public static final int MENU_RENDER_DISTANCE_STEP = 8;

    /**
     * The tighter distance {@code AUTO} applies while the player stands in a template, in blocks.
     *
     * <p>Lives here beside its sibling so the config comment can name it and the two numbers are
     * read together; the rule that uses it is
     * {@code EditorMenusModeState.withinRange}.</p>
     */
    public static final int AUTO_TEMPLATE_DISTANCE_BLOCKS = 15;

    /** Silent. A real setting, not a "not configured" sentinel — see {@link #getTrainEngineVolume()}. */
    public static final double MIN_TRAIN_ENGINE_VOLUME = 0.0;
    /** The engine at the volume the distance curve computes, unscaled. */
    public static final double MAX_TRAIN_ENGINE_VOLUME = 1.0;
    /** Ships unscaled — the curve in {@code TrainEngineSound} is the intended mix. */
    public static final double DEFAULT_TRAIN_ENGINE_VOLUME = 1.0;

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
    public static final ModConfigSpec.DoubleValue TRAIN_ENGINE_VOLUME;
    public static final ModConfigSpec.BooleanValue DELETE_WORLD_ON_REBOARD;
    /** Tiles per row in the Train Builder's Open screen grid. See {@link #getBuilderTilesPerRow()}. */
    public static final ModConfigSpec.IntValue BUILDER_TILES_PER_ROW;
    /** Cap on how far the editor's world-space menus draw. See {@link #getMenuRenderDistance()}. */
    public static final ModConfigSpec.IntValue MENU_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue SKYBOX_PUNCH_ENABLED;
    public static final ModConfigSpec.BooleanValue PORTAL_CROSSING_FADE;
    public static final ModConfigSpec.BooleanValue SCRIBBLE_COLOR_PICKER_VISIBLE;
    public static final ModConfigSpec.BooleanValue CINEMATIC_HOTKEY_ENABLED;
    public static final ModConfigSpec.BooleanValue CREATIVE_SHIFT_CLICK_TO_HOTBAR;
    /**
     * Relay pool ids of community (player-written) books this player has read, stored as decimal strings.
     * GLOBAL client-side read history — persists across worlds and servers (unlike the retired per-world
     * server set), so "read once = read everywhere" survives even when the relay can't personalise the
     * pool (older relay, no network consent, or offline). See {@link games.brennan.dungeontrain.event.SharedBookReadMirror}.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHARED_BOOKS_READ;
    /** The player's most recent NPS ("recommend") answer (0-10), or -1 if never answered. */
    public static final ModConfigSpec.IntValue DEATH_SCREEN_LAST_NPS;
    /**
     * Which tier of other-players' content this client accepts — see {@link ContentMode}. Client-scope
     * so it follows the player across worlds and servers, like the dev-consent and shared-book-read
     * state above; the server learns it per-player via {@code ContentModeSyncPacket}.
     */
    public static final ModConfigSpec.EnumValue<ContentMode> CONTENT_MODE;
    /**
     * The config deviation the player last chose to keep, as a stable signature (see
     * {@code ConfigDeviationPromptHandler}). Empty when they have never dismissed the launch
     * prompt. Signature rather than a plain opt-out flag so a NEW change is still surfaced —
     * dismissing once doesn't silently sign the player up to every future edit.
     */
    public static final ModConfigSpec.ConfigValue<String> CONFIG_DEVIATION_ACKNOWLEDGED;
    /**
     * Whether the player has dismissed the DPI-bypass warning for good (see
     * {@code DpiBypassPromptHandler}). A plain opt-out rather than a signature: unlike a config
     * deviation there is nothing here that can change into a NEW thing worth re-asking about — the
     * tool is either running or it isn't, and someone who knows why it's running has heard enough.
     */
    public static final ModConfigSpec.BooleanValue DPI_BYPASS_WARNING_OPTED_OUT;
    public static final ModConfigSpec.BooleanValue BOOK_AUTHOR_BURN_CHAT;

    /**
     * Remembered answer to the custom-Train-Editor-content prompt — see
     * {@link CustomContentPreference}. {@code ASK} means keep prompting.
     */
    public static final ModConfigSpec.EnumValue<CustomContentPreference> CUSTOM_CONTENT_PREFERENCE;
    /** The last answer actually given, whether or not it was remembered. */
    public static final ModConfigSpec.EnumValue<CustomContentPreference> CUSTOM_CONTENT_LAST_ANSWER;

    /**
     * Whether the player wants community content the relay tagged as politically sensitive filtered
     * out of what they are served.
     *
     * <p>Tri-state on purpose. {@link PoliticalFilter#UNSET} is not a third preference — it means the
     * player has not been asked yet, and it resolves to a DIFFERENT effective answer depending on their
     * client language (see {@code PoliticalFilterPrefs}): ON for a Chinese locale, OFF otherwise. A
     * plain boolean would have to pick one default for everybody and would forget whether the player
     * had ever actually chosen it.</p>
     */
    public static final ModConfigSpec.EnumValue<PoliticalFilter> POLITICAL_FILTER;

    /** The player's answer to the Political Filter prompt. See {@link #POLITICAL_FILTER}. */
    public enum PoliticalFilter {
        /** Never asked (or asked and dismissed before the prompt existed) — resolved from the locale. */
        UNSET,
        /** Filter tagged content out. */
        ON,
        /** Show everything. */
        OFF
    }

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
        TRAIN_ENGINE_VOLUME = pair.getLeft().trainEngineVolume;
        DELETE_WORLD_ON_REBOARD = pair.getLeft().deleteWorldOnReboard;
        BUILDER_TILES_PER_ROW = pair.getLeft().builderTilesPerRow;
        MENU_RENDER_DISTANCE = pair.getLeft().menuRenderDistance;
        SKYBOX_PUNCH_ENABLED = pair.getLeft().skyboxPunchEnabled;
        PORTAL_CROSSING_FADE = pair.getLeft().portalCrossingFade;
        SCRIBBLE_COLOR_PICKER_VISIBLE = pair.getLeft().scribbleColorPickerVisible;
        CINEMATIC_HOTKEY_ENABLED = pair.getLeft().cinematicHotkeyEnabled;
        CREATIVE_SHIFT_CLICK_TO_HOTBAR = pair.getLeft().creativeShiftClickToHotbar;
        SHARED_BOOKS_READ = pair.getLeft().sharedBooksRead;
        DEATH_SCREEN_LAST_NPS = pair.getLeft().deathScreenLastNps;
        POLITICAL_FILTER = pair.getLeft().politicalFilter;
        CONTENT_MODE = pair.getLeft().contentMode;
        CUSTOM_CONTENT_PREFERENCE = pair.getLeft().customContentPreference;
        CUSTOM_CONTENT_LAST_ANSWER = pair.getLeft().customContentLastAnswer;
        CONFIG_DEVIATION_ACKNOWLEDGED = pair.getLeft().configDeviationAcknowledged;
        DPI_BYPASS_WARNING_OPTED_OUT = pair.getLeft().dpiBypassWarningOptedOut;
        BOOK_AUTHOR_BURN_CHAT = pair.getLeft().bookAuthorBurnChat;
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

        b.push("sound");
        ModConfigSpec.DoubleValue trainEngineVolume = b
                .comment("How loud the train engine loop plays, 0.0 (off) to 1.0 (unscaled). Multiplies the volume Dungeon Train already computes from your distance to the nearest carriage, so the falloff and the in-carriage maximum keep their shape - this only scales the whole curve. Separate from vanilla's Ambient slider, which also moves cave sounds and mob ambience. 0.0 stops the sound outright rather than looping it silently. Also settable in Options -> Music & Sounds, in Options -> Dungeon Train..., and in the X menu -> Options.")
                .defineInRange("trainEngineVolume", DEFAULT_TRAIN_ENGINE_VOLUME,
                        MIN_TRAIN_ENGINE_VOLUME, MAX_TRAIN_ENGINE_VOLUME);
        b.pop();

        b.push("skybox");
        ModConfigSpec.BooleanValue skyboxPunchEnabled = b
                .comment("Let Skybox Blocks show the real sky through them. The effect writes the block's shape into the depth buffer just after the sky is drawn, so whatever sits behind it is never drawn over the sky. Set false to turn Skybox Blocks into plain invisible solid blocks instead - the escape hatch if the effect misbehaves with your graphics setup. Automatically off while a shader pack is loaded, which needs its own handling.")
                .define("punchEnabled", true);
        b.pop();

        b.push("portal");
        ModConfigSpec.BooleanValue portalCrossingFade = b
                .comment("Fade a portal carriage's lighting into a flat hold as you walk toward the middle of its corridor, instead of leaving each copy lit by its own doorway. A portal carriage and the twin you are swapped into are built from the same blocks, but only one of them has a real door onto the train, so light leaks into one and not the other and the brightness can jump as you cross - most visibly near the train door, where turning round is enough to swap you. The hold is the same constant in both copies, so there is nothing left for the crossing to change; it ramps in from each doorway and is at full strength between the baffles. Set false for the old hard cut.")
                .define("crossingFade", true);
        b.pop();

        b.push("scribble");
        ModConfigSpec.BooleanValue scribbleColorPickerVisible = b
                .comment("Show the Scribble mod's 16-swatch colour picker on the book-writing screen. Off by default: Dungeon Train keeps the book screen close to vanilla, and the picker is the one part of Scribble that changes what a book LOOKS like rather than how it is edited. No in-game control by design — flip this by hand to get the swatches back. Has no effect unless the Scribble mod is installed.")
                .define("colorPickerVisible", false);
        b.pop();

        b.push("cinematic");
        ModConfigSpec.BooleanValue cinematicHotkeyEnabled = b
                .comment("Let the cinematographer hotkey (C by default, rebindable under Controls > Dungeon Train) replay the intro cinematic while you are in spectator mode. Turn this off to reclaim the key for something else without unbinding it. Only the hotkey is affected - /dungeontrain cinematic still works either way.")
                .define("hotkeyEnabled", true);
        b.pop();

        b.push("creative");
        ModConfigSpec.BooleanValue creativeShiftClickToHotbar = b
                .comment("Shift-clicking an item in the creative menu a second time drops that full stack",
                         "straight into your hotbar (and a third, fourth... click fills the next slot along).",
                         "The first shift-click is untouched — it still just maxes the stack on your cursor.",
                         "Turn this off for pure vanilla creative-menu behaviour.")
                .define("shiftClickToHotbar", true);
        b.pop();

        b.push("world");
        ModConfigSpec.BooleanValue deleteWorldOnReboard = b
                .comment("Delete the old world's save folder when reboarding (creating a fresh world) from the death screen. Dungeon Train is designed around a new world per run, so this defaults on to keep the world list and disk clean. Only auto-generated \"<prefix> <timestamp>\" saves (Dungeon Train / Dev World / World) are ever deleted — renamed or hand-made worlds and editor worlds are always kept. Toggleable in-game via the trash icon next to the reboard button.")
                .define("deleteOnReboard", true);
        b.pop();

        b.push("builderOpen");
        // The bounds are duplicated from BuilderTemplateGridLayout's MIN_COLUMNS/MAX_COLUMNS rather
        // than shared, because that class is client-only and package-private while this config is
        // common-side. Safe to duplicate: this range only rejects a hand-edited value, and the grid
        // clamps whatever it is handed anyway — so a drift here can widen what the file accepts, not
        // what the screen draws.
        ModConfigSpec.IntValue builderTilesPerRow = b
                .comment("How many template tiles per row the Train Builder's Open screen lays out. The grid block stays the same width whichever you pick, so a higher number shows more of a library at once by making each tile smaller. Set in-game from the numbered button beside the Open screen's controls (left-click for more, right-click for fewer) — on a small window or a high GUI scale the count stops below 6, where a further column would no longer fit.")
                .defineInRange("tilesPerRow", 3, 2, 6);
        b.pop();

        b.push("editorMenus");
        ModConfigSpec.IntValue menuRenderDistance = b
                .comment("How far away, in blocks, the editor's world-space menus keep drawing — plot panels, the row-start nav menus, the help board, the package and Stages panels. Applies whether or not you are standing in a template, and in both the On and Auto menu modes. Auto additionally tightens to " + AUTO_TEMPLATE_DISTANCE_BLOCKS + " blocks while you are inside a template, so the smaller of the two wins there. Set in-game from the Menu Distance row of the editor's X-menu.")
                .defineInRange("menuRenderDistance", DEFAULT_MENU_RENDER_DISTANCE,
                        MIN_MENU_RENDER_DISTANCE, MAX_MENU_RENDER_DISTANCE);
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

        b.push("contentMode");
        ModConfigSpec.EnumValue<ContentMode> contentMode = b
                .comment("Which tier of other players' content this game accepts.",
                         "ADULT (default) — everything: community books written by strangers as chest loot,",
                         "  player-written narrative series on lecterns, Death Note curses, carriages built in",
                         "  other worlds, and direct chat with the developer.",
                         "KID — no developer chat in either direction; community books restricted to those",
                         "  explicitly flagged kid-safe (a stricter bar than the normal moderation pass); lectern",
                         "  narratives, Death Note curses and shared carriages off, since there is no kid-safe",
                         "  curation for those. You can still write and share your own books and carriages —",
                         "  what you publish is additionally screened for contact/personal information.",
                         "On a multiplayer server, dev chat and community books follow YOUR setting; lectern",
                         "narratives and shared carriages are world-shared and follow the host's.",
                         "Asked once on the first-launch consent card; changeable in Options -> Dungeon Train...")
                .defineEnum("mode", ContentMode.ADULT);
        b.pop();

        b.push("customContent");
        ModConfigSpec.EnumValue<CustomContentPreference> customContentPreference = b
                .comment("What to do when a world starts with custom Train Editor content active",
                         "(your own edits, or an imported dtpack).",
                         "ASK (default) — show the prompt each time a world is entered for the first time.",
                         "CONTINUE — always play with the custom content. Those runs are Free Play: they",
                         "  earn advancements live but don't count towards your cross-world profile or stats.",
                         "DISABLE — always turn custom content off, so the world runs the bundled game and",
                         "  your stats count. Set from the prompt's \"Remember decision\" checkbox, or the",
                         "Custom Train Content row in Options -> Dungeon Train...")
                .defineEnum("preference", CustomContentPreference.ASK);
        ModConfigSpec.EnumValue<CustomContentPreference> customContentLastAnswer = b
                .comment("The last answer you gave the custom-content prompt, recorded whether or not",
                         "you ticked \"Remember decision\". Reused when a run reboards automatically",
                         "(immediate respawn), where there is no menu to ask from.",
                         "ASK means you have never answered. Managed automatically — not meant to be",
                         "edited by hand.")
                .defineEnum("lastAnswer", CustomContentPreference.ASK);
        b.pop();

        b.push("deathScreen");
        ModConfigSpec.IntValue deathScreenLastNps = b
                .comment("Internal: the player's most recent NPS (\"how likely to recommend\") answer, 0-10, or -1 if never answered. Used to decide when the death-screen donation page appears. Managed automatically.")
                .defineInRange("lastNpsScore", -1, -1, 10);
        b.pop();

        b.push("contentFilter");
        ModConfigSpec.EnumValue<PoliticalFilter> politicalFilter = b
                .comment("Whether community books and player narratives flagged as politically sensitive are",
                         "filtered out of what you're served. ON hides them, OFF shows everything, UNSET means",
                         "you haven't been asked — which reads as ON for Chinese-language clients (who are the",
                         "ones offered the choice) and OFF for everyone else. Set from the prompt on the title",
                         "screen, or the Political Filter row in Options > Dungeon Train.")
                .defineEnum("politicalFilter", PoliticalFilter.UNSET);
        b.pop();

        b.push("configIntegrity");
        ModConfigSpec.ConfigValue<String> configDeviationAcknowledged = b
                .comment("Internal: the Dungeon Train config change you last chose to keep at the launch prompt,",
                         "recorded as a signature of exactly what had been changed. The prompt stays quiet while",
                         "the config still matches that, and asks again if you change something else. Empty = never",
                         "dismissed. Managed automatically — clear it by hand to be asked again.")
                .define("deviationAcknowledged", "");
        b.pop();

        b.push("connectionWarnings");
        ModConfigSpec.BooleanValue dpiBypassWarningOptedOut = b
                .comment("Internal: set when you dismiss the \"connection blocker detected\" notice for good.",
                         "That notice appears on the title screen when a DPI-bypass tool (zapret, GoodbyeDPI)",
                         "is running, because those tools can stop Dungeon Train reaching brennan.games. The",
                         "check is local — a look at running process names, nothing sent anywhere. Set this",
                         "back to false to be told again.")
                .define("dpiBypassWarningOptedOut", false);
        b.pop();

        b.push("books");
        ModConfigSpec.BooleanValue bookAuthorBurnChat = b
                .comment("Print a chat line crediting the author each time a Dungeon Train book burns",
                         "(\"The book by X burns\") — the books you read, throw away or drop on death all",
                         "catch fire, and this is the only place their writer is named after the fact.",
                         "Toggle in-game via Options > Dungeon Train, or the X menu -> Options. Off by default.")
                .define("authorBurnChat", false);
        b.pop();

        return new Holder(allScale, worldspaceChannel, hudChannel, developerPopupShownBefore, developerPopupOptedOut, freePlayConfirmOptedOut,
                devConsentGranted, devConsentGrantSession, devConsentLastMsgToDev, openedAdvancementsBefore,
                rideSnapshotsEnabled, rideSnapshotIntervalSeconds, rideSnapshotMaxStored, rideSnapshotChatLog,
                rideSnapshotMinFps, rideSnapshotMinTps,
                rideSnapshotDiskOffload, rideSnapshotFlushMinFps, rideSnapshotFlushMinTps, rideSnapshotMaxOnDisk,
                rideSnapshotMaxResolution,
                framerateThrottleEnabled, framerateThrottleFps, trainEngineVolume, skyboxPunchEnabled, portalCrossingFade, scribbleColorPickerVisible, cinematicHotkeyEnabled, creativeShiftClickToHotbar, deleteWorldOnReboard,
                builderTilesPerRow,
                menuRenderDistance,
                sharedBooksRead,
                deathScreenLastNps, politicalFilter, contentMode, customContentPreference,
                customContentLastAnswer,
                configDeviationAcknowledged, dpiBypassWarningOptedOut, bookAuthorBurnChat);
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

    // ----- Config deviation prompt (see client/ConfigDeviationPromptHandler) -----

    /**
     * The deviation signature the player last chose to keep, or {@code ""} when they have never
     * dismissed the prompt. Compared against the CURRENT signature, so changing the config again
     * re-arms the prompt.
     */
    public static String getConfigDeviationAcknowledged() {
        return isLoaded() ? CONFIG_DEVIATION_ACKNOWLEDGED.get() : "";
    }

    /** Remember this exact deviation as "keep my changes". Pass {@code ""} to re-arm the prompt. */
    public static void setConfigDeviationAcknowledged(String signature) {
        if (!isLoaded()) return;
        if (CONFIG_DEVIATION_ACKNOWLEDGED.get().equals(signature)) return; // skip a needless TOML write
        CONFIG_DEVIATION_ACKNOWLEDGED.set(signature);
        CONFIG_DEVIATION_ACKNOWLEDGED.save();
    }

    // ----- DPI-bypass warning (see client/DpiBypassPromptHandler) -----

    /** Whether the player has told the DPI-bypass warning to stop appearing. */
    public static boolean isDpiBypassWarningOptedOut() {
        return isLoaded() && DPI_BYPASS_WARNING_OPTED_OUT.get();
    }

    /** Record "don't show again" (or clear it, to be warned once more next launch). */
    public static void setDpiBypassWarningOptedOut(boolean value) {
        if (!isLoaded()) return;
        if (DPI_BYPASS_WARNING_OPTED_OUT.get() == value) return; // skip a needless TOML write
        DPI_BYPASS_WARNING_OPTED_OUT.set(value);
        DPI_BYPASS_WARNING_OPTED_OUT.save();
    }

    // ----- Political content filter (see client/PoliticalFilterPrefs) -----

    /**
     * The player's RAW stored answer — {@code UNSET} when they have never been asked. Callers deciding
     * whether to actually filter want {@code PoliticalFilterPrefs.isEnabled()} instead, which resolves
     * {@code UNSET} against their client language. Reads {@code UNSET} before the config loads, which
     * is the same "not answered yet" the prompt keys off.
     */
    public static PoliticalFilter getPoliticalFilter() {
        return isLoaded() ? POLITICAL_FILTER.get() : PoliticalFilter.UNSET;
    }

    public static void setPoliticalFilter(PoliticalFilter value) {
        if (!isLoaded() || value == null) return;
        POLITICAL_FILTER.set(value);
        POLITICAL_FILTER.save();
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
    /** The player's most recent NPS ("recommend") answer (0-10), or -1 if never answered. */
    public static int getLastNpsScore() {
        return isLoaded() ? DEATH_SCREEN_LAST_NPS.get() : -1;
    }

    /** Persist the player's latest NPS answer. Idempotent — skips the TOML write when unchanged. */
    public static void setLastNpsScore(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(-1, Math.min(10, value));
        if (DEATH_SCREEN_LAST_NPS.get() == clamped) return;
        DEATH_SCREEN_LAST_NPS.set(clamped);
        DEATH_SCREEN_LAST_NPS.save();
    }

    public static void setOpenedAdvancementsBefore(boolean value) {
        if (!isLoaded()) return;
        if (OPENED_ADVANCEMENTS_BEFORE.get() == value) return;
        OPENED_ADVANCEMENTS_BEFORE.set(value);
        OPENED_ADVANCEMENTS_BEFORE.save();
    }

    /**
     * Put every "you have seen this once already" client flag back to its first-run value: the
     * advancements-keybind hint, the developer-popup and Free Play confirm opt-outs, and the last NPS
     * answer. Used by the Video Tools profile reset, whose whole job is making the mod behave as if
     * this install had never been played.
     *
     * <p>Deliberately narrow: display preferences the player tuned (snapshots, framerate, filters)
     * are choices, not progress, and are left alone. One {@code .save()} covers the whole TOML.</p>
     */
    /**
     * True when any first-run flag has moved off its fresh-install value — i.e. there is something
     * for {@link #resetFirstRunFlags} to do. Lets the reset screen say "nothing to reset" honestly.
     */
    public static boolean hasFirstRunFlagsSet() {
        if (!isLoaded()) return false;
        return OPENED_ADVANCEMENTS_BEFORE.get()
            || DEVELOPER_POPUP_OPTED_OUT.get()
            || FREE_PLAY_CONFIRM_OPTED_OUT.get()
            || DEATH_SCREEN_LAST_NPS.get() >= 0;
    }

    public static void resetFirstRunFlags() {
        if (!isLoaded()) return;
        OPENED_ADVANCEMENTS_BEFORE.set(false);
        DEVELOPER_POPUP_OPTED_OUT.set(false);
        FREE_PLAY_CONFIRM_OPTED_OUT.set(false);
        DEATH_SCREEN_LAST_NPS.set(-1);
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

    /**
     * Print "The book by X burns" in chat each time a Dungeon Train book catches fire? Off by
     * default. Toggled from Options &gt; Dungeon Train and the X menu &rarr; Options.
     *
     * <p>The burn itself is decided server-side, so the setter also pushes the new value over
     * {@link games.brennan.dungeontrain.net.BookAuthorChatSyncPacket} — see
     * {@link games.brennan.dungeontrain.client.BookAuthorChatSyncClient}.</p>
     */
    public static boolean isBookAuthorBurnChatEnabled() {
        return isLoaded() && BOOK_AUTHOR_BURN_CHAT.get();
    }

    public static void setBookAuthorBurnChat(boolean value) {
        if (!isLoaded()) return;
        BOOK_AUTHOR_BURN_CHAT.set(value);
        BOOK_AUTHOR_BURN_CHAT.save();
        BookAuthorChatSyncClient.syncNow();
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

    // ----- Train engine volume (see client/sound/TrainEngineVolume) -----

    /**
     * How loud the train engine loop plays: {@code 0.0} (off) to {@code 1.0} (the computed curve,
     * unscaled). Multiplies what {@code TrainEngineSound} works out from the player's distance to the
     * nearest carriage.
     *
     * <p>Defaults to {@link #DEFAULT_TRAIN_ENGINE_VOLUME} ({@code 1.0}) <b>including pre-load</b>, and
     * that direction matters: {@code 0.0} is a real setting meaning silence, so a config that hasn't
     * loaded yet must read as full rather than accidentally muting the engine for the first ticks of a
     * world. The opposite fail-safe to {@link #isFramerateThrottleEnabled()}, for the same reason it is
     * the opposite there — a missing config should leave behaviour alone, and here "alone" is loud.</p>
     */
    public static double getTrainEngineVolume() {
        return isLoaded() ? snapToTenth(TRAIN_ENGINE_VOLUME.get()) : DEFAULT_TRAIN_ENGINE_VOLUME;
    }

    /**
     * Persist the engine volume, clamped and snapped to a tenth like the display scales.
     *
     * <p>Idempotent, and here that is load-bearing rather than tidiness: the vanilla-style slider on
     * the sound screen calls this continuously while it is dragged. Snapping to tenths bounds a whole
     * drag to eleven distinct values, and skipping the unchanged write bounds it to eleven TOML
     * writes.</p>
     */
    public static void setTrainEngineVolume(double value) {
        if (!isLoaded()) return;
        double snapped = snapToTenth(Math.max(MIN_TRAIN_ENGINE_VOLUME,
                Math.min(MAX_TRAIN_ENGINE_VOLUME, value)));
        if (TRAIN_ENGINE_VOLUME.get() == snapped) return;
        TRAIN_ENGINE_VOLUME.set(snapped);
        TRAIN_ENGINE_VOLUME.save();
        // "Sound Check" — the server can't see a client-config change, so tell it.
        // Loaded lazily here: this setter only ever runs on a client (the spec is a
        // CLIENT config), so a dedicated server never touches the client class.
        games.brennan.dungeontrain.client.sound.TrainVolumeAdvancement.onVolumeChanged();
    }

    // ----- Delete old world on reboard (death-screen trash toggle) -----

    /**
     * Should Skybox Blocks punch a hole to the sky? Defaults to {@code true}, and to
     * {@code true} pre-load as well — the block is inert without it, so the safe
     * fallback is the effect being on.
     */
    public static boolean isSkyboxPunchEnabled() {
        return !isLoaded() || SKYBOX_PUNCH_ENABLED.get();
    }

    /**
     * Should a portal corridor's lighting fade into a flat hold across its crossing? Defaults to
     * {@code true}, and to {@code true} pre-load as well, on the same rule as the flag above: the
     * effect is what stops the swap from popping, so the safe fallback while the TOML is still
     * loading is the effect being on.
     *
     * <p>Read once per lightmap rebuild by {@code LightTexturePortalCrossingMixin} — about 20 times
     * a second, which is why it is a plain flag read and not a listener.</p>
     */
    public static boolean isPortalCrossingFadeEnabled() {
        return !isLoaded() || PORTAL_CROSSING_FADE.get();
    }

    /**
     * Show Scribble's colour-swatch grid on the book-writing screen? Defaults to {@code false}.
     *
     * <p>Note this reads {@code isLoaded() &&}, not the {@code !isLoaded() ||} form used by the
     * defaults-on flags above: the hidden default has to hold on the very first frame too, before
     * the client TOML is loaded, or the swatches would flash in on a fresh install.</p>
     *
     * <p>Read by {@link games.brennan.dungeontrain.client.ScribbleColorPickerToggle}. Inert
     * without the Scribble mod, which is a modpack companion rather than a dependency.</p>
     */
    public static boolean isScribbleColorPickerVisible() {
        return isLoaded() && SCRIBBLE_COLOR_PICKER_VISIBLE.get();
    }

    /**
     * Persist the Scribble colour-picker toggle. Idempotent: skips the {@code .save()} (a TOML
     * write) when the value is unchanged. Currently only reachable by editing the TOML; see ScribbleColorPickerToggle.SHOW_TOGGLE_BUTTON.
     */
    public static void setScribbleColorPickerVisible(boolean value) {
        if (!isLoaded()) return;
        if (SCRIBBLE_COLOR_PICKER_VISIBLE.get() == value) return;
        SCRIBBLE_COLOR_PICKER_VISIBLE.set(value);
        SCRIBBLE_COLOR_PICKER_VISIBLE.save();
    }

    /**
     * Does the cinematographer hotkey replay the cinematic? Defaults to {@code true}, and to
     * {@code true} pre-load as well — the key doing nothing until the config lands would read as
     * a dead binding rather than as a setting.
     */
    public static boolean isCinematicHotkeyEnabled() {
        return !isLoaded() || CINEMATIC_HOTKEY_ENABLED.get();
    }

    /** Persist the cinematic-hotkey toggle. Idempotent: skips the TOML write when unchanged. */
    public static void setCinematicHotkeyEnabled(boolean value) {
        if (!isLoaded()) return;
        if (CINEMATIC_HOTKEY_ENABLED.get() == value) return;
        CINEMATIC_HOTKEY_ENABLED.set(value);
        CINEMATIC_HOTKEY_ENABLED.save();
    }

    /**
     * Does a repeat shift-click in the creative menu send the stack to the hotbar? Defaults to
     * {@code true}, and to {@code true} pre-load as well — the creative menu can be open before
     * the config lands, and the feature silently missing reads worse than it being on.
     */
    public static boolean isCreativeShiftClickToHotbar() {
        return !isLoaded() || CREATIVE_SHIFT_CLICK_TO_HOTBAR.get();
    }

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

    // ----- Train Builder Open screen: tiles per row -----

    /**
     * Tiles per row in the Open screen's template grid. Defaults to 3 — today's fixed count — both
     * before the config loads and when it never does, so a client with no config file draws the
     * grid it has always drawn.
     *
     * <p>The player's raw choice, which is not necessarily what is on screen: a narrow window or a
     * high GUI scale can hold fewer columns than this, and {@code BuilderTemplateGridLayout} clamps
     * to what fits. Storing the ask rather than the clamped result is deliberate — a count saturated
     * away by a small window comes back when the window grows again.</p>
     */
    public static int getBuilderTilesPerRow() {
        return isLoaded() ? BUILDER_TILES_PER_ROW.get() : 3;
    }

    /**
     * Persist the tiles-per-row choice. Idempotent — skips the TOML write when unchanged, because
     * this is driven by a button a player clicks repeatedly while watching the grid reflow.
     */
    public static void setBuilderTilesPerRow(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(2, Math.min(6, value));
        if (BUILDER_TILES_PER_ROW.get() == clamped) return;
        BUILDER_TILES_PER_ROW.set(clamped);
        BUILDER_TILES_PER_ROW.save();
    }

    // ----- Editor world-space menu render distance -----

    /**
     * How far the editor's world-space menus keep drawing, in blocks. Falls back to
     * {@link #DEFAULT_MENU_RENDER_DISTANCE} before the config loads and when it never does, so a
     * client with no config file behaves like one that has just accepted the default.
     *
     * <p>This is the cap that applies everywhere — in a template or between plots, in Auto or On.
     * Auto layers its own tighter in-template rule on top; whichever is smaller wins.</p>
     */
    public static int getMenuRenderDistance() {
        return isLoaded() ? MENU_RENDER_DISTANCE.get() : DEFAULT_MENU_RENDER_DISTANCE;
    }

    /**
     * Persist the menu render distance. Idempotent — skips the TOML write when unchanged, because
     * this is driven by a button the player clicks repeatedly while watching panels appear and
     * disappear around them.
     */
    public static void setMenuRenderDistance(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_MENU_RENDER_DISTANCE, Math.min(MAX_MENU_RENDER_DISTANCE, value));
        if (MENU_RENDER_DISTANCE.get() == clamped) return;
        MENU_RENDER_DISTANCE.set(clamped);
        MENU_RENDER_DISTANCE.save();
    }

    /**
     * The next value up from {@code current}, snapped to a multiple of
     * {@link #MENU_RENDER_DISTANCE_STEP}.
     *
     * <p>Snapping rather than plain addition so the numbers the player lands on read cleanly:
     * from the {@link #MIN_MENU_RENDER_DISTANCE} floor of 5 the first step goes to 8, then 16, 24,
     * and so on, instead of the 13 / 21 / 29 an offset-by-five ladder would give.</p>
     */
    public static int stepMenuRenderDistanceUp(int current) {
        int next = (current / MENU_RENDER_DISTANCE_STEP + 1) * MENU_RENDER_DISTANCE_STEP;
        return Math.min(MAX_MENU_RENDER_DISTANCE, next);
    }

    /** The next value down from {@code current}, snapped the same way, floored at the minimum. */
    public static int stepMenuRenderDistanceDown(int current) {
        int down = current % MENU_RENDER_DISTANCE_STEP == 0
            ? current - MENU_RENDER_DISTANCE_STEP
            : (current / MENU_RENDER_DISTANCE_STEP) * MENU_RENDER_DISTANCE_STEP;
        return Math.max(MIN_MENU_RENDER_DISTANCE, down);
    }

    // ----- Content mode (Adult / Kid) — see ContentMode -----

    /**
     * Which tier of other-players' content this client accepts. Defaults to {@link ContentMode#ADULT},
     * including before the config loads.
     *
     * <p>Note this is the opposite of the fail-safe direction the SERVER mirror uses: there, an unknown
     * player reads as KID so a missing sync can never leak adult content. Here the value IS the local
     * setting rather than an assumption about a stranger — defaulting a pre-load read to KID would blink
     * the title-screen chat affordance off and back on during startup for every adult player.</p>
     */
    public static ContentMode getContentMode() {
        return isLoaded() ? CONTENT_MODE.get() : ContentMode.ADULT;
    }

    /** Convenience for the gate call sites: is this client in Kid mode? */
    public static boolean isKidMode() {
        return getContentMode().isKid();
    }

    /**
     * Persist the content mode. Idempotent — skips the TOML write when unchanged. Driven by the
     * first-launch consent card and by the Options row; no-op pre-load.
     */
    public static void setContentMode(ContentMode mode) {
        if (!isLoaded() || mode == null) return;
        if (CONTENT_MODE.get() == mode) return;
        CONTENT_MODE.set(mode);
        CONTENT_MODE.save();
    }

    // ----- Custom Train Editor content prompt (see CustomContentPromptClient) -----

    /**
     * This client's remembered answer to the custom-content prompt. Defaults to
     * {@link CustomContentPreference#ASK}, including before the config loads — a pre-load read must
     * never silently answer a prompt on the player's behalf.
     */
    public static CustomContentPreference getCustomContentPreference() {
        return isLoaded() ? CUSTOM_CONTENT_PREFERENCE.get() : CustomContentPreference.ASK;
    }

    /**
     * Persist the remembered answer. Idempotent — skips the TOML write when unchanged. Driven by
     * the prompt's "Remember decision" checkbox and by the Options row; no-op pre-load.
     */
    public static void setCustomContentPreference(CustomContentPreference preference) {
        if (!isLoaded() || preference == null) return;
        if (CUSTOM_CONTENT_PREFERENCE.get() == preference) return;
        CUSTOM_CONTENT_PREFERENCE.set(preference);
        CUSTOM_CONTENT_PREFERENCE.save();
    }

    /**
     * The last answer the player actually gave the prompt, recorded on every answer rather than
     * only on "Remember decision". {@link CustomContentPreference#ASK} means never answered.
     *
     * <p>Exists because the remembered <em>preference</em> can't serve this: it is only written
     * when the checkbox is ticked, so a player who answers each world individually has no recorded
     * answer at all — and the automatic reboard has no menu to ask from.</p>
     */
    public static CustomContentPreference getLastCustomContentAnswer() {
        return isLoaded() ? CUSTOM_CONTENT_LAST_ANSWER.get() : CustomContentPreference.ASK;
    }

    /** Record an answer. Idempotent; no-op pre-load. */
    public static void setLastCustomContentAnswer(CustomContentPreference answer) {
        if (!isLoaded() || answer == null) return;
        if (CUSTOM_CONTENT_LAST_ANSWER.get() == answer) return;
        CUSTOM_CONTENT_LAST_ANSWER.set(answer);
        CUSTOM_CONTENT_LAST_ANSWER.save();
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
            ModConfigSpec.DoubleValue trainEngineVolume,
            ModConfigSpec.BooleanValue skyboxPunchEnabled,
            ModConfigSpec.BooleanValue portalCrossingFade,
            ModConfigSpec.BooleanValue scribbleColorPickerVisible,
            ModConfigSpec.BooleanValue cinematicHotkeyEnabled,
            ModConfigSpec.BooleanValue creativeShiftClickToHotbar,
            ModConfigSpec.BooleanValue deleteWorldOnReboard,
            ModConfigSpec.IntValue builderTilesPerRow,
            ModConfigSpec.IntValue menuRenderDistance,
            ModConfigSpec.ConfigValue<List<? extends String>> sharedBooksRead,
            ModConfigSpec.IntValue deathScreenLastNps,
            ModConfigSpec.EnumValue<PoliticalFilter> politicalFilter,
            ModConfigSpec.EnumValue<ContentMode> contentMode,
            ModConfigSpec.EnumValue<CustomContentPreference> customContentPreference,
            ModConfigSpec.EnumValue<CustomContentPreference> customContentLastAnswer,
            ModConfigSpec.ConfigValue<String> configDeviationAcknowledged,
            ModConfigSpec.BooleanValue dpiBypassWarningOptedOut,
            ModConfigSpec.BooleanValue bookAuthorBurnChat
    ) {}
}
