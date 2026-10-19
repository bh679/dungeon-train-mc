package games.brennan.dungeontrain.client.live.deathpages;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.Watcher;
import games.brennan.dungeontrain.client.live.life.LiveLifeTracker;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Dev builds only: {@code /dungeontrain-debug-death-pages <variant>} opens the death screen on the
 * Live Eye pages with made-up counters for one variant (on_air, ended_early, taken, nobody, quicker),
 * then saves a window screenshot of each page to {@code screenshots/} — Gate 2 evidence without any
 * desktop capture. {@code all} walks every variant in turn.
 *
 * <p>Headless runs, where nobody can type the command, drop a {@code death-pages-preview.flag} file
 * holding the variant into the game directory: the preview starts once a world has been joined for
 * {@link #FLAG_DELAY_TICKS} ticks, and the flag is deleted so it runs once.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveDeathPagesPreview {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "preview-";
    private static final long DAY = 86_400_000L;
    /** Ticks to let a page's fade settle before the shot. */
    private static final int SETTLE_TICKS = 50;
    private static final int FLAG_DELAY_TICKS = 200;
    private static final String FLAG_FILE = "death-pages-preview.flag";
    private static int joinedTicks;

    private static final Map<String, Function<Long, LiveLifeStats>> VARIANTS = Map.of(
        "on_air", LiveDeathPagesPreview::onAir,
        "ended_early", LiveDeathPagesPreview::endedEarly,
        "taken", LiveDeathPagesPreview::taken,
        "nobody", LiveDeathPagesPreview::nobody,
        "quicker", LiveDeathPagesPreview::onAir);
    private static final List<String> ORDER = List.of("on_air", "ended_early", "taken", "nobody", "quicker");

    private static List<String> queue = List.of();
    private static NarrativeDeathScreen screen;
    private static String current;
    private static int step;
    private static int ticks;
    private static String grabName;

    private LiveDeathPagesPreview() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.getDispatcher().register(Commands.literal("dungeontrain-debug-death-pages")
            .then(Commands.argument("variant", StringArgumentType.word()).executes(ctx -> {
                String v = StringArgumentType.getString(ctx, "variant");
                List<String> run = "all".equals(v) ? ORDER : VARIANTS.containsKey(v) ? List.of(v) : List.of();
                if (run.isEmpty()) return 0;
                Minecraft.getInstance().execute(() -> start(run));
                return 1;
            })));
    }

    private static void start(List<String> run) {
        queue = run;
        next();
    }

    private static void next() {
        if (queue.isEmpty()) {
            current = null;
            screen = null;
            LiveRewards.preview(null);
            LOGGER.info("[DungeonTrain] death-pages preview done");
            return;
        }
        current = queue.get(0);
        queue = queue.subList(1, queue.size());
        long now = System.currentTimeMillis();
        String life = PREFIX + current;
        LiveLifeStats stats = VARIANTS.get(current).apply(now);
        LiveLifeTracker.update(life, s -> stats, false, now);
        LiveRewards.preview(rewardsFor(current, now));
        screen = NarrativeDeathScreen.livePreview(life);
        Minecraft.getInstance().setScreen(screen);
        step = 0;
        ticks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (current == null) {
            pollFlag(mc);
            return;
        }
        if (mc.screen != screen) { current = null; LiveRewards.preview(null); return; } // closed by hand
        if (++ticks < SETTLE_TICKS) return;
        ticks = 0;
        if (step == 0) {
            grabName = "live-death-" + current + "-broadcast.png";
            screen.previewLivePage(true);
            step = 1;
        } else {
            grabName = "live-death-" + current + "-crowd.png";
            step = 2;
        }
    }

    private static void pollFlag(Minecraft mc) {
        if (!DungeonTrain.isDevBuild() || mc.player == null) { joinedTicks = 0; return; }
        if (++joinedTicks != FLAG_DELAY_TICKS) return;
        java.nio.file.Path flag = mc.gameDirectory.toPath().resolve(FLAG_FILE);
        if (!java.nio.file.Files.isRegularFile(flag)) return;
        try {
            String v = java.nio.file.Files.readString(flag).trim();
            java.nio.file.Files.deleteIfExists(flag);
            List<String> run = "all".equals(v) ? ORDER : VARIANTS.containsKey(v) ? List.of(v) : List.of();
            LOGGER.info("[DungeonTrain] death-pages preview from flag: {}", run);
            if (!run.isEmpty()) start(run);
        } catch (java.io.IOException e) {
            LOGGER.warn("[DungeonTrain] death-pages preview flag unreadable: {}", e.toString());
        }
    }

    /** Shots are taken after a full frame so the page is on the buffer, not mid-draw. */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        String name = grabName;
        if (name == null) return;
        grabName = null;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
            msg -> LOGGER.info("[DungeonTrain] death-pages preview {} -> {}", name, msg.getString()));
        if (step == 2) next();
    }

    // ---- made-up lives ----------------------------------------------------------------------------

    /** Two stints: 10 minutes long ago, then one from {@code secondStart} still running. */
    private static final String MOTH = "069a79f444e94726a5befca90e38aaf5";
    private static final String ROOK = "853c80ef3c3749fdaa49938b674adae6";
    /** A website watcher whose Discord is linked to a game account. */
    private static final String KESTREL = "4566e69fc90748ee8d71d7ba5aa00d20";
    private static final LiveLifeStats.Author GAME_MOTH = new LiveLifeStats.Author("game", MOTH);
    private static final LiveLifeStats.Author GAME_ROOK = new LiveLifeStats.Author("game", ROOK);
    private static final LiveLifeStats.Author WEB = new LiveLifeStats.Author("web", null);
    private static final LiveLifeStats.Author TWITCH = new LiveLifeStats.Author("twitch", null);

    private static LiveLifeStats watched(long now, long secondStart, int peak) {
        return LiveLifeStats.empty("").stintStarted(secondStart - 3_600_000L, 10)
            .stintEnded(secondStart - 3_000_000L, "left").stintStarted(secondStart, 10)
            .heartbeat(now - 5000, peak)
            .moment(false).moment(true).moment(false).moment(true)
            .chat(now - 300_000, "Rook", "keep left", GAME_ROOK).chat(now - 250_000, "Rook", "jump", GAME_ROOK)
            .chat(now - 200_000, "Rook", "left again", GAME_ROOK).chat(now - 120_000, "Rook", "the bridge!", GAME_ROOK)
            .chat(now - 110_000, "Rook", "go go", GAME_ROOK).chat(now - 100_000, "twitchfan", "pog", TWITCH)
            .chat(now - 40_000, "Moth", "noooo the bridge", GAME_MOTH).chat(now - 20_000, "Kestrel", "gg, that was a long one", WEB)
            .reacted(101, 5).reacted(102, 4).voteClosed("p1", true)
            .crowd(List.of(new Watcher("Moth", MOTH, "game", 4140),
                new Watcher("Kestrel", KESTREL, "web", 3120),
                new Watcher(null, null, "web", 2820),
                new Watcher("Rook", ROOK, "game", 2600)));
    }

    private static LiveLifeStats onAir(long now) {
        return watched(now, now - 62 * 60_000L, 41).died().taken("Rook", false);
    }

    private static LiveLifeStats endedEarly(long now) {
        // 38 + 10 minutes on air, ending two hours before the fall.
        return watched(now, now - 2 * 3_600_000L - 38 * 60_000L, 23).stintEnded(now - 2 * 3_600_000L, "left");
    }

    private static LiveLifeStats taken(long now) {
        return watched(now, now - 3_600_000L - 21 * 60_000L, 17).taken("Rook", true).stintEnded(now - 3_600_000L, "removed");
    }

    private static LiveLifeStats nobody(long now) {
        return LiveLifeStats.empty("").stintStarted(now - 19 * 60_000L, 10).moment(false).died();
    }

    private static LiveRewards rewardsFor(String variant, long now) {
        if (!"quicker".equals(variant) && !"nobody".equals(variant)) return LiveRewards.NONE;
        return new LiveRewards(List.of(
            new LiveRewards.Reward(LiveRewards.QUICKER_STREAMS, now + 26 * DAY, "Kestrel", List.of("Moth")),
            new LiveRewards.Reward(LiveRewards.REPLAYS_KEPT, now + 30 * DAY, "Moth", List.of())));
    }
}
