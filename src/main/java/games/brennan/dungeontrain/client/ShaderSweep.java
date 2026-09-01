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
    private static final int JOIN_TIMEOUT_TICKS = 3600;

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

    private enum Phase { BOOT, JOINING, SETTLE, SITE_SETUP, SITE_WAIT, CAPTURE, CAPTURE_WAIT, FINISHED }

    /** One measurement stop: the commands that reach it, and how long it needs to settle. */
    private record Site(String id, List<String> commands, int settleTicks) {}

    private static Phase phase = Phase.BOOT;
    private static int timer = BOOT_TICKS;
    private static List<Site> sites = List.of();
    private static int siteIndex = 0;
    private static int commandIndex = 0;
    private static boolean worldRequested = false;

    /** Set by the tick loop, consumed by the render hook on the next completed frame. */
    private static volatile String pendingCapture = null;

    /** Last screen class dismissed, so a screen that reopens every tick is logged once. */
    private static String lastDismissed = "";

    private ShaderSweep() {}

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
        String name = pendingCapture;
        if (name == null) return;
        pendingCapture = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getMainRenderTarget() == null) return;
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> { });
        LOGGER.info("[DungeonTrain] sweep captured {}", name);
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

    /** One command per tick. A burst down one tick is the kind of thing a command source drops. */
    private static void tickSiteSetup(Minecraft mc) {
        Site site = sites.get(siteIndex);
        if (commandIndex < site.commands().size()) {
            String command = site.commands().get(commandIndex++);
            LOGGER.info("[DungeonTrain] sweep[{}]: /{}", site.id(), command);
            CommandRunner.run(command);
            return;
        }
        phase = Phase.SITE_WAIT;
        timer = site.settleTicks();
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
        pendingCapture = String.format(Locale.ROOT, "sweep-%s-%s.png", ShaderCompat.token(), site.id());
        logPanel(site);
        phase = Phase.CAPTURE_WAIT;
        timer = CAPTURE_TICKS;
    }

    private static void tickCaptureWait() {
        if (--timer > 0) return;
        siteIndex++;
        commandIndex = 0;
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

        // Skybox blocks: a wall of one variant four blocks ahead, then face it. Placed rather than
        // hunted for, so the site exists in any world.
        out.add(new Site("04-skybox-blocks", List.of(
            "gamemode creative",
            "fill ~4 ~-1 ~-3 ~4 ~4 ~3 dungeontrain:skybox_end",
            "tp @s ~ ~ ~ -90 0"), SITE_TICKS));

        // A real dimensional carriage on a real train — the only way to reach the fog, the room's
        // sky lift and the corridor ramp together, since all three are driven by the live swap.
        //
        // Raising the rate is not enough on its own. A group only carries a corridor if its blocks
        // were LAID under the rate that claims it, so an existing train answers `portal tp` with
        // "no corridor is stamped in it" — which is exactly what the first run got. Re-seeding the
        // train at a fresh X after the rate change is what makes the groups stamped ones.
        if (plainX != Integer.MIN_VALUE) {
            out.add(new Site("05-carriage-setup", List.of(
                "gamemode creative",
                "dungeontrain portal carriage 1",
                "dtp " + plainX), CARRIAGE_TICKS));
            out.add(new Site("06-carriage-inside", List.of(
                "dungeontrain portal tp"), SITE_TICKS));
            // A step further down the corridor: the transition ramp is a function of how far along
            // it the camera is, so the arrival point alone never shows it engaged.
            out.add(new Site("07-carriage-corridor", List.of(
                "tp @s ^ ^ ^6"), SITE_TICKS));
        } else {
            LOGGER.warn("[DungeonTrain] sweep: no band-free stretch found — carriage sites skipped");
        }

        return out;
    }

    private static void addBandSite(List<Site> out, String id, double fromX, DoubleUnaryOperator ramp) {
        int x = scanForBand(fromX, ramp);
        if (x == Integer.MIN_VALUE) {
            LOGGER.warn("[DungeonTrain] sweep: no '{}' band found within {} blocks — site skipped",
                id, SCAN_LIMIT_BLOCKS);
            return;
        }
        LOGGER.info("[DungeonTrain] sweep: '{}' band found at x={}", id, x);
        out.add(new Site(id, List.of("dtp " + x), SITE_TICKS));
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
