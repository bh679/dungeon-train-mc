package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Drives the whole shader-compatibility sweep from inside the client, so a pack can be measured
 * with one shell command and no hands on the keyboard.
 *
 * <h2>Why the mod drives itself</h2>
 * <p>The sweep is eleven packs across five sites, and it has to be re-run after every fix in the
 * phases that follow — the cost that matters is the cost of the tenth run, not the first. Driving
 * it from outside needs synthetic keystrokes, which means macOS Accessibility, which means the run
 * commandeers the screen for as long as it takes. Driving it from inside needs none of that: the
 * capture reads the framebuffer, so the window can sit behind other work, and every site is reached
 * by a command rather than by a click at a remembered pixel.</p>
 *
 * <h2>Running it</h2>
 * <pre>
 *   ./gradlew runClient -PshaderSweep="&lt;world folder name&gt;"
 * </pre>
 * <p>Off unless that property is set, so an ordinary dev client is untouched. Shots land in
 * {@code run/screenshots/} named {@code sweep-&lt;pack&gt;-&lt;site&gt;.png}, with the F3+5 panel up in
 * every one — the panel is what DT asked for, the image is what the pack did, and the pair is the
 * matrix cell.</p>
 *
 * <p>The run logs {@value #DONE_MARKER} and then <b>quits the game</b>, so the wrapper script's
 * ordinary path is just "wait for gradle to exit" — no process-tree killing on a machine that is
 * running other work. The quit follows the sequence vanilla's own Save-and-Quit uses, and which
 * this codebase learned the hard way: {@code ClientLevel#disconnect} to make the integrated server
 * start shutting down, <em>then</em> {@code Minecraft#disconnect} to wait for it. {@code stop()}
 * alone spins forever on that wait. The wrapper still carries a deadline, because a wedged quit
 * must not wedge the whole sweep.</p>
 *
 * <h2>Failure is per-site</h2>
 * <p>A site that cannot be reached logs and is skipped rather than aborting the run: a sweep that
 * returns four cells and names the fifth as unreachable is worth far more than one that returns
 * nothing. Every skip is logged with its reason so the matrix can say so honestly.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ShaderSweep {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code -PshaderSweep=<world folder>} in build.gradle becomes this. */
    private static final String PROPERTY = "dungeontrain.shader_sweep";

    /**
     * {@code -PshaderSweepSites=05,06,07} — visit only the sites whose id starts with one of these.
     *
     * <p>A fix to one site should not cost a re-run of all eight across twelve packs. The sites
     * before the filtered ones still run their commands, because a later site's position depends on
     * where the earlier ones left the player; only their captures are skipped.</p>
     */
    private static final String SITES_PROPERTY = "dungeontrain.shader_sweep_sites";

    /** The line the shell wrapper watches for to know the run is over. */
    public static final String DONE_MARKER = "[DungeonTrain] SHADER SWEEP COMPLETE";

    /** Ticks to let the title screen settle before opening the world. */
    private static final int BOOT_TICKS = 100;
    /**
     * Ticks to wait for the world to finish loading.
     *
     * <p>Generous on purpose. A first run set this to the settle budget (20s) and gave up while the
     * server was still reading data packs — the load was healthy, the deadline was wrong. A world
     * with Sable, shaders and a cold chunk cache can take well over a minute, and the cost of
     * waiting too long is a slower sweep while the cost of waiting too little is no sweep at all.</p>
     */
    private static final int JOIN_TIMEOUT_TICKS = 12000;

    /** Ticks after the player exists before the first site — chunks, the train, the join popups. */
    private static final int SETTLE_TICKS = 400;
    /** Ticks between the capture request and moving on, covering the async PNG write. */
    private static final int CAPTURE_TICKS = 60;
    /** Ticks a plain teleport is given to load chunks and settle the band state. */
    private static final int SITE_TICKS = 400;
    /**
     * The carriage setup has to re-seed the train and let it lay fresh groups under the new portal
     * rate, which is slower than any other site by a wide margin.
     */
    private static final int CARRIAGE_TICKS = 900;

    /** Band scan: how far ahead of the player to look, and in what steps. */
    private static final int SCAN_LIMIT_BLOCKS = 400_000;
    private static final int SCAN_STEP_BLOCKS = 64;
    /** Band intensity counted as "fully inside" for a capture. */
    private static final double SCAN_TARGET = 0.95;

    /**
     * The authored dimensional carriage the carriage sites stamp. A tiling room on purpose — the
     * room fog only engages for the modes that tile, so a one-off room would measure the fog system
     * as "never asked" and say nothing about the pack.
     */
    private static final String CARRIAGE_ROOM = "backrooms";

    /** Where a band shot is taken from: above every build, clear of the track, looking up. */
    private static final int BAND_VIEW_Y = 250;
    private static final int BAND_VIEW_Z = 0;
    /** Pitched up far enough to fill the frame with sky, shallow enough to keep a horizon for scale. */
    private static final int BAND_VIEW_PITCH = -55;

    /** A command of the form {@code wait:<ticks>} pauses the setup instead of being sent. */
    private static final String WAIT_PREFIX = "wait:";

    private enum Phase { BOOT, JOINING, SETTLE, SITE_SETUP, SITE_WAIT, CAPTURE, CAPTURE_WAIT, FINISHED }

    /** One measurement stop: the commands that reach it, and how long it needs to settle. */
    private record Site(String id, List<String> commands, int settleTicks) {}

    private static Phase phase = Phase.BOOT;
    private static int timer = BOOT_TICKS;
    private static List<Site> sites = List.of();
    private static int siteIndex = 0;
    private static int commandIndex = 0;
    private static int setupWait = 0;
    private static boolean worldRequested = false;

    /** Set by the tick loop, consumed by the render hook on the next completed frame. */
    private static volatile String pendingCapture = null;

    /** Last screen class dismissed, so a screen that reopens every tick is logged once. */
    private static String lastDismissed = "";

    /**
     * Frames actually rendered, counted by the render hook.
     *
     * <p>This exists because of a run that produced eight screenshots of which seven were
     * byte-identical. The panel values in the log moved from site to site exactly as they should,
     * so every check that looked at DT's own state said the sweep was working — but macOS throttles
     * an occluded window's rendering to nothing, so the framebuffer never changed while the ticks
     * carried on. A stale capture is the worst possible failure here: it is not an error, it is a
     * confident wrong answer, and it would have been copied into the matrix as fact.</p>
     */
    private static volatile long framesRendered = 0;

    /** Frame count when the current site's settle began, so staleness is measurable per site. */
    private static long framesAtSiteStart = 0;

    /** Frames a site must render before its capture is trustworthy. */
    private static final int MIN_FRAMES_PER_SITE = 30;

    private ShaderSweep() {}

    /** Whether this site is in the filter, or there is no filter. */
    private static boolean wanted(Site site) {
        String filter = System.getProperty(SITES_PROPERTY);
        if (filter == null || filter.isBlank()) return true;
        for (String want : filter.split(",")) {
            if (site.id().startsWith(want.trim())) return true;
        }
        return false;
    }

    /** The world folder to open, or {@code null} when the sweep is off. */
    public static String world() {
        String raw = System.getProperty(PROPERTY);
        return raw == null || raw.isBlank() ? null : raw;
    }

    public static boolean enabled() {
        return world() != null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled() || phase == Phase.FINISHED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        switch (phase) {
            case BOOT -> tickBoot(mc);
            case JOINING -> tickJoining(mc);
            case SETTLE -> tickSettle(mc);
            case SITE_SETUP -> tickSiteSetup(mc);
            case SITE_WAIT -> tickSiteWait(mc);
            case CAPTURE -> tickCapture(mc);
            case CAPTURE_WAIT -> tickCaptureWait();
            default -> { }
        }
    }

    /**
     * The capture itself, on a completed frame. {@code ClientTickEvent} would grab whatever the
     * framebuffer happened to hold mid-draw; {@code RenderFrameEvent.Post} is after the frame is
     * whole, GUI included — which matters here, because the panel <em>is</em> half the measurement.
     */
    @SubscribeEvent
    public static void onRenderFrameEnd(RenderFrameEvent.Post event) {
        framesRendered++;
        String name = pendingCapture;
        if (name == null) return;
        pendingCapture = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getMainRenderTarget() == null) return;
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> { });
        long frames = framesRendered - framesAtSiteStart;
        if (frames < MIN_FRAMES_PER_SITE) {
            // Loud, because the image will look plausible and be wrong. Almost always an occluded
            // or minimised window: bring it to the front and run again.
            LOGGER.error("[DungeonTrain] sweep captured {} after only {} frame(s) — THE IMAGE IS "
                + "PROBABLY STALE. The window is likely occluded or minimised; macOS stops "
                + "rendering it while ticks continue.", name, frames);
        } else {
            LOGGER.info("[DungeonTrain] sweep captured {} ({} frames)", name, frames);
        }
    }

    private static void tickBoot(Minecraft mc) {
        if (mc.level != null) {
            // Already in a world (a dev auto-join beat us to it) — take it and skip the open.
            phase = Phase.JOINING;
            timer = JOIN_TIMEOUT_TICKS;
            return;
        }
        if (--timer > 0) return;
        if (!(mc.screen instanceof TitleScreen)) {
            timer = 20; // not there yet; look again shortly
            return;
        }
        if (worldRequested) return;
        worldRequested = true;

        String levelId = world();
        File savesDir = new File(mc.gameDirectory, "saves");
        if (!new File(savesDir, levelId).isDirectory()) {
            LOGGER.error("[DungeonTrain] sweep: no such world folder '{}' under {}", levelId, savesDir);
            finish("world folder missing");
            return;
        }
        LOGGER.info("[DungeonTrain] sweep: opening world '{}' with pack {}", levelId, ShaderCompat.describe());
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.openWorld(levelId, () -> {
            LOGGER.error("[DungeonTrain] sweep: world open failed for '{}'", levelId);
            finish("world open failed");
        });
        phase = Phase.JOINING;
        timer = JOIN_TIMEOUT_TICKS;
    }

    private static void tickJoining(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (--timer <= 0) {
                LOGGER.error("[DungeonTrain] sweep: timed out waiting to join after {} ticks",
                    JOIN_TIMEOUT_TICKS);
                finish("join timeout");
            }
            return;
        }
        unpause(mc);
        LOGGER.info("[DungeonTrain] sweep: joined; settling for {} ticks", SETTLE_TICKS);
        phase = Phase.SETTLE;
        timer = SETTLE_TICKS;
    }

    private static void tickSettle(Minecraft mc) {
        dismissScreen(mc);
        if (--timer > 0) return;
        showPanel();
        if (!packLoadedAsRequested()) return;
        sites = buildSites(mc);
        if (sites.isEmpty()) {
            finish("no sites could be built");
            return;
        }
        LOGGER.info("[DungeonTrain] sweep: {} site(s) to visit: {}", sites.size(),
            sites.stream().map(Site::id).toList());
        siteIndex = 0;
        commandIndex = 0;
        phase = Phase.SITE_SETUP;
    }

    /**
     * One command per tick, with {@code wait:<ticks>} pauses between them.
     *
     * <p>One per tick because a burst down a single tick is the kind of thing a command source
     * drops. The pauses are for the commands that depend on the world catching up with the previous
     * one: {@code /fill} right after a long {@code /tp} answered "that position is not loaded",
     * because the chunk had not arrived yet, and the editor's settings commands have to be issued
     * from inside the plot the {@code enter} before them is still travelling to.</p>
     */
    private static void tickSiteSetup(Minecraft mc) {
        Site site = sites.get(siteIndex);
        if (commandIndex == 0 && setupWait == 0) framesAtSiteStart = framesRendered;
        if (setupWait > 0) {
            setupWait--;
            return;
        }
        if (commandIndex < site.commands().size()) {
            String command = site.commands().get(commandIndex++);
            if (command.startsWith(WAIT_PREFIX)) {
                setupWait = parseWait(command);
                LOGGER.info("[DungeonTrain] sweep[{}]: waiting {} ticks", site.id(), setupWait);
                return;
            }
            LOGGER.info("[DungeonTrain] sweep[{}]: /{}", site.id(), command);
            CommandRunner.run(command);
            return;
        }
        phase = Phase.SITE_WAIT;
        timer = site.settleTicks();
    }

    private static int parseWait(String command) {
        try {
            return Math.max(0, Integer.parseInt(command.substring(WAIT_PREFIX.length()).trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("[DungeonTrain] sweep: bad wait '{}' — treated as none", command);
            return 0;
        }
    }

    private static void tickSiteWait(Minecraft mc) {
        unpause(mc);
        dismissScreen(mc);
        showPanel();
        if (--timer > 0) return;
        phase = Phase.CAPTURE;
    }

    private static void tickCapture(Minecraft mc) {
        Site site = sites.get(siteIndex);
        if (!wanted(site)) {
            LOGGER.info("[DungeonTrain] sweep[{}]: not in the site filter — visited, not captured", site.id());
            phase = Phase.CAPTURE_WAIT;
            timer = 1;
            return;
        }
        pendingCapture = String.format(Locale.ROOT, "sweep-%s-%s.png", ShaderCompat.token(), site.id());
        logPanel(site);
        phase = Phase.CAPTURE_WAIT;
        timer = CAPTURE_TICKS;
    }

    private static void tickCaptureWait() {
        if (--timer > 0) return;
        siteIndex++;
        commandIndex = 0;
        setupWait = 0;
        if (siteIndex >= sites.size()) {
            finish("all sites visited");
            return;
        }
        phase = Phase.SITE_SETUP;
    }

    /**
     * The sites, in the order they are visited. Band stops are located by scanning each ramp
     * forward from the player rather than hard-coded, because the bands are a function of the
     * world's own cycle config and a fixed X would silently measure the wrong place.
     */
    private static List<Site> buildSites(Minecraft mc) {
        List<Site> out = new ArrayList<>();
        double here = mc.player == null ? 0.0 : mc.player.getX();

        int plainX = scanForPlain(here);

        out.add(new Site("00-plain", List.of("gamemode creative"), SITE_TICKS));

        addBandSite(out, "01-band-void", here, ClientVoidBand::endSkyIntensityAt);
        addBandSite(out, "02-band-nether", here, ClientNetherBand::netherIntensityAt);
        addBandSite(out, "03-band-upsidedown", here, ClientUpsideDownBand::upsideDownIntensityAt);

        // Skybox blocks: a wall of one variant in open, static air.
        //
        // The obvious version — fill a wall in front of the player and turn to face it — measured
        // nothing: the player stands on a moving train, so the wall is laid in world space and the
        // train carries the camera away from it before the shot. The control run caught this
        // honestly (14 cubes indexed, drew=no, camera inside a carriage looking at a door), which
        // is precisely the kind of false "the shader broke it" the control exists to prevent.
        // Spectator well off the track holds the camera still, and open sky behind the wall is
        // what makes the hole legible at all.
        if (plainX != Integer.MIN_VALUE) {
            out.add(new Site("04-skybox-blocks", List.of(
                // Everything here is absolute, and the chunk is forceloaded before anything is
                // asked of it. Two earlier shapes both answered "that position is not loaded":
                // a bare teleport to a distant coordinate (nothing had ever asked the server for
                // that chunk), and `~` offsets while standing on the train — a rider sits on a
                // Sable sub-level, so the server resolves `~` in far shipyard plot space rather
                // than in the track coordinates the camera is actually looking at.
                "gamemode spectator",
                "forceload add " + (plainX - 16) + " 184 " + (plainX + 16) + " 216",
                "tp @s " + plainX + " 250 200 -90 0",
                WAIT_PREFIX + "200",
                "fill " + (plainX + 4) + " 247 197 " + (plainX + 4) + " 253 203 dungeontrain:skybox_end"),
                SITE_TICKS));
        }

        // A real dimensional carriage on a real train — the only way to reach the fog, the room's
        // sky lift and the corridor ramp together, since all three are driven by the live swap.
        //
        // Raising the rate is not enough on its own. A group only carries a corridor if its blocks
        // were LAID under the rate that claims it, so an existing train answers `portal tp` with
        // "no corridor is stamped in it" — which is exactly what the first run got. Re-seeding the
        // train at a fresh X after the rate change is what makes the groups stamped ones.
        // The dimensional carriage, reached through the editor rather than through play.
        //
        // `portal tp` was the wrong door: it teleports to a HALLWAY portal, so the control run sat
        // in an ordinary carriage with room=NONE. Raising the carriage rate and re-seeding the
        // train was the wrong door too — a group only carries a corridor if its blocks were laid
        // under the rate that claims it, and even then finding the group is a matter of luck.
        // `portal test` stamps the authored room as a twin — [plug][corridor][room][corridor][plug]
        // — and puts the camera at the corridor mouth facing down it, which is deterministic. It
        // insists on being run from inside a room's plot, hence the editor hop first.
        // The room's own authored settings decide whether there is anything to measure: the fog is
        // only sent for a mode that fogs (`if (!structure.mode().fogs()) return`), and the sky lift
        // only for a room that names a sky. The control found `backrooms` doing neither, which
        // would have read as "the pack discarded it" for a system that was never asked. Forced here
        // so a zero is always the pack's doing.
        out.add(new Site("05-carriage-plot", List.of(
            "gamemode creative",
            // Sweep the previous run's stamp first. Every sweep stamps a twin into the same
            // basement lane, and they accumulate across runs in a save that is reused twelve times
            // over: the two Complementary packs measured a room cleanly and every pack after them
            // read room=NONE with the identical setup chat, which is what leftover stamps look
            // like. `back` is the command's own undo, so this is what it is for.
            "dungeontrain portal test back",
            WAIT_PREFIX + "60",
            "dungeontrain editor portals enter " + CARRIAGE_ROOM,
            WAIT_PREFIX + "160",
            "dungeontrain editor portals mode endless_repetition",
            "dungeontrain editor portals sky day",
            WAIT_PREFIX + "60",
            // Drop into the room's own box. The editor leaves the camera standing ON the plot
            // floor, which the diagnostics showed is a couple of blocks ABOVE the box the server
            // names — cam y=238 against roomBox y=230..236 — so the lift was never applied to a
            // camera that was, by inches, outside the room it was measuring.
            "tp @s ~ ~-5 ~ 0 0",
            WAIT_PREFIX + "60"), CARRIAGE_TICKS));
        // Both captures are taken INSIDE the room. `portal test` lands the camera in the doorway,
        // which is on the edge of the room's own box — and the corridor ramp that would carry the
        // lift across that edge is dead in a twin with no train, so a doorway shot is a coin toss
        // between t=1.000 and t=0.000. Stepping in first makes the reading deterministic.
        out.add(new Site("06-carriage-room", List.of(
            "dungeontrain portal test",
            WAIT_PREFIX + "120",
            "tp @s ^ ^ ^14"), SITE_TICKS));
        out.add(new Site("07-carriage-deep", List.of(
            "tp @s ^ ^ ^8"), SITE_TICKS));

        return out;
    }

    /**
     * A band stop, framed on the sky rather than on whatever the train happens to be pointing at.
     *
     * <p>The first sweep took these shots from wherever {@code /dtp} left the camera — on the train,
     * usually inside a carriage. Every frame came back near black, and the numbers said so: mean
     * luma 10-13 of 255 for every pack including the control. That measures the inside of a
     * carriage, not the band's sky. The upside-down band was the clearest case, because its day sky
     * is drawn <em>below</em> the train and was never in shot at all.</p>
     *
     * <p>So: ride in with {@code /dtp} (which is what makes the band state and the chunks real),
     * then step off it. Spectator holds the camera still where a rider would be carried away;
     * absolute coordinates over a forceloaded chunk because a rider sits on a Sable sub-level and
     * the server resolves {@code ~} in far shipyard space; Y 250 is above every build; and the pitch
     * puts the dome in frame. Band intensity is a function of world-X alone, so moving up and
     * sideways costs nothing.</p>
     */
    private static void addBandSite(List<Site> out, String id, double fromX, DoubleUnaryOperator ramp) {
        int x = scanForBand(fromX, ramp);
        if (x == Integer.MIN_VALUE) {
            LOGGER.warn("[DungeonTrain] sweep: no '{}' band found within {} blocks — site skipped",
                id, SCAN_LIMIT_BLOCKS);
            return;
        }
        LOGGER.info("[DungeonTrain] sweep: '{}' band found at x={}", id, x);
        out.add(new Site(id, List.of(
            "gamemode creative",
            "dtp " + x,
            WAIT_PREFIX + "300",
            "gamemode spectator",
            "forceload add " + (x - 16) + " " + (BAND_VIEW_Z - 16) + " " + (x + 16) + " " + (BAND_VIEW_Z + 16),
            "tp @s " + x + " " + BAND_VIEW_Y + " " + BAND_VIEW_Z + " 0 " + BAND_VIEW_PITCH,
            WAIT_PREFIX + "200"), SITE_TICKS));
    }

    /**
     * First world-X at or beyond {@code fromX} clear of all three bands, which is where the
     * carriage sites are staged: a portal room measured inside the Nether band would be reporting
     * two effects at once and neither cleanly.
     */
    private static int scanForPlain(double fromX) {
        for (int offset = 0; offset <= SCAN_LIMIT_BLOCKS; offset += SCAN_STEP_BLOCKS) {
            double x = fromX + offset;
            if (ClientVoidBand.endSkyIntensityAt(x) <= 0.0
                && ClientNetherBand.netherIntensityAt(x) <= 0.0
                && ClientUpsideDownBand.upsideDownIntensityAt(x) <= 0.0) {
                return (int) x;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * First world-X at or beyond {@code fromX} where the ramp reads fully inside its band, or
     * {@link Integer#MIN_VALUE} if there is none in range. The ramps are pure functions of X, so
     * this is a cheap arithmetic scan and touches no chunks.
     */
    private static int scanForBand(double fromX, DoubleUnaryOperator ramp) {
        for (int offset = 0; offset <= SCAN_LIMIT_BLOCKS; offset += SCAN_STEP_BLOCKS) {
            double x = fromX + offset;
            if (ramp.applyAsDouble(x) >= SCAN_TARGET) return (int) x;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Write the panel's values to the log as well as into the pixels. The screenshot is the record
     * a human reads; this is the one that can be grepped across eleven runs.
     */
    private static void logPanel(Site site) {
        // The per-frame values come from the last frame the HUD drew, not from the live fields:
        // those are cleared the moment the panel has drawn them, and this runs on a tick, so
        // reading them live logged zeros under a screenshot that plainly showed otherwise.
        ShaderDiagnostics.Frame f = ShaderDiagnostics.lastDrawn();
        LOGGER.info("[DungeonTrain] sweep[{}] pack={} bandSky(void={} nether={} flip={}) "
                + "fogColour={} fogDist(asked={} far={}->{} cancelled={}) "
                + "skybox(cubes={} drew={} stencil={}) room({} t={} lift={}) crossing={}",
            site.id(), ShaderCompat.describe(),
            ShaderDiagnostics.fmt(f.skyVoid()),
            ShaderDiagnostics.fmt(f.skyNether()),
            ShaderDiagnostics.fmt(f.skyUpsideDown()),
            f.fogColorSource().isEmpty() ? "none" : f.fogColorSource()
                + " " + ShaderDiagnostics.hex(f.fogColorIn())
                + "->" + ShaderDiagnostics.hex(f.fogColorOut()),
            f.fogDistanceAsked(),
            ShaderDiagnostics.fmt(f.fogVanillaFar()),
            ShaderDiagnostics.fmt(f.fogFar()),
            f.fogCancelled(),
            f.skyboxCubes(), f.skyboxDrew(), f.skyboxStencil(),
            // The lightmap-paced values are not per-frame and are never cleared, so they are read live.
            ShaderDiagnostics.roomSkyKind().isEmpty() ? "none" : ShaderDiagnostics.roomSkyKind(),
            ShaderDiagnostics.fmt(ShaderDiagnostics.roomSkyT()),
            ShaderDiagnostics.fmt(ShaderDiagnostics.roomSkyLift()),
            ShaderDiagnostics.fmt(ShaderDiagnostics.crossingT()));
        logRoomGeometry(site);
    }

    /**
     * Where the camera is, and which room boxes the server has named it.
     *
     * <p>The carriage sites read {@code room(NONE t=0)} for eight packs out of ten with byte-identical
     * setup chat, and {@code t=0} alone cannot say whether the server never sent a room or sent one
     * the camera is standing outside. These two lines answer that in one run instead of another
     * sweep.</p>
     */
    private static void logRoomGeometry(Site site) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        LOGGER.info("[DungeonTrain] sweep[{}] cam=({}, {}, {}) roomBox={} fogBox={}",
            site.id(),
            String.format(Locale.ROOT, "%.1f", mc.player.getX()),
            String.format(Locale.ROOT, "%.1f", mc.player.getY()),
            String.format(Locale.ROOT, "%.1f", mc.player.getZ()),
            ClientPortalRoomSky.describeRegion(),
            ClientPortalRoomFog.describeRegion());
    }

    /**
     * Refuse to measure a pack that did not actually load.
     *
     * <p>FOOTAGE 1.0 fails to compile on Iris 1.8.14 ({@code Unable to parse scale directive}), and
     * Iris answers by disabling shaders and carrying on. The sweep then ran happily with no pack at
     * all and, because the filename is keyed on the <em>active</em> pack, wrote its shots over
     * {@code sweep-none-*} — silently replacing the control with a second copy of itself. A pack
     * that cannot load is a result worth reporting; it is not a licence to overwrite the baseline.</p>
     */
    private static boolean packLoadedAsRequested() {
        String requested = GraphicsCapabilities.configuredShaderPack();
        if (requested.isEmpty()) return true;          // the control: no pack was asked for
        if (ShaderCompat.active()) return true;        // asked for one, got one
        LOGGER.error("[DungeonTrain] sweep: '{}' was requested but no pack is active — it failed to "
            + "load (see the Iris errors above). Capturing nothing, so the control is not "
            + "overwritten by a run that had no shaders in it.", requested);
        finish("pack failed to load: " + requested);
        return false;
    }

    /** Make sure the panel is up — every shot has to carry the ask, or it is only half a record. */
    private static void showPanel() {
        if (!ShaderDiagnostics.visible()) ShaderDiagnostics.toggleVisible();
    }

    /**
     * Stop the game pausing itself when the window is not in front.
     *
     * <p>Not a nicety — it is what makes an unattended sweep possible at all. The whole point of
     * driving from inside the mod is that the window can sit behind other work, but a background
     * window is an unfocused one, and vanilla answers that by opening the pause screen. In
     * singleplayer that also halts the integrated server, so the train stops, chunks stop loading
     * and every site would be photographed in a frozen world. The first run hit exactly this: the
     * join succeeded and then the log filled with one dismissed {@code PauseScreen} per tick,
     * forever, because dismissing it does nothing about the reason it opened.</p>
     *
     * <p>Set in code as well as in {@code run/options.txt} so the sweep does not depend on a file
     * that a fresh clone or a reset profile would not have.</p>
     */
    private static void unpause(Minecraft mc) {
        if (mc.options == null) return;
        if (mc.options.pauseOnLostFocus) {
            mc.options.pauseOnLostFocus = false;
            LOGGER.info("[DungeonTrain] sweep: pauseOnLostFocus off — the window may sit in the background");
        }
    }

    /**
     * Close whatever popup is in the way. DT paints several screens over a join and a couple more
     * on first launch; any of them would sit in the middle of a screenshot. Only ever called once
     * the player exists, so the load screens this would otherwise interrupt are already past.
     *
     * <p>Logged on change rather than per call: a screen that reopens every tick is a bug worth
     * seeing once, not twenty lines a second drowning the sites either side of it.</p>
     */
    private static void dismissScreen(Minecraft mc) {
        if (mc.screen == null || mc.player == null) return;
        String name = mc.screen.getClass().getSimpleName();
        if (!name.equals(lastDismissed)) {
            LOGGER.info("[DungeonTrain] sweep: dismissing {}", name);
            lastDismissed = name;
        }
        mc.setScreen(null);
    }

    private static void finish(String why) {
        phase = Phase.FINISHED;
        LOGGER.info("{} ({}) pack={}", DONE_MARKER, why, ShaderCompat.describe());
        quitGame(Minecraft.getInstance());
    }

    /**
     * Leave the world and close the game.
     *
     * <p>Both disconnects are needed and in this order. {@code Minecraft#disconnect} blocks on
     * {@code while (!integratedserver.isShutdown())} but does not itself signal the server to halt;
     * {@code ClientLevel#disconnect} is what sends the packet that starts the shutdown cascade.
     * Calling only the second hangs on "Saving level" forever — the same trap the death screen's
     * New World button fell into.</p>
     */
    private static void quitGame(Minecraft mc) {
        if (mc == null) return;
        try {
            if (mc.level != null) {
                mc.level.disconnect();
                mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            }
            mc.stop();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] sweep: quit failed, leaving the client up", t);
        }
    }
}
