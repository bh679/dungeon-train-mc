package games.brennan.dungeontrain.client.support;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DonationOptionsScreen;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dev builds only. {@code /dungeontrain-debug-contribute <state>} opens the Contribute page with a
 * made-up rewards state, so every featured-card and shelf state can be seen before the relay takes
 * reward gifts: {@code none} (all off), {@code some} (two on, one with a queued month), {@code all}
 * (all five on), {@code give} (as {@code some}, every Give button enabled), {@code relay} (back to
 * the relay's real state).
 *
 * <p>Headless runs drop a {@code contribute-preview.flag} file in the game directory: on the title
 * screen the client then walks every state across the Contribute page, the death screen's window
 * and the rewards page, saving one {@code Screenshot.grab} of the game's own framebuffer per step
 * to {@code screenshots/contribute-*.png}, and deletes the flag.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ContributePreviewCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String FLAG_FILE = "contribute-preview.flag";
    private static final int SETTLE_TICKS = 30;

    private record Step(String name, String state, Function<Screen, Screen> screen) {}

    private static final List<Step> SWEEP = List.of(
            new Step("contribute-none", "none", ContributeScreen::new),
            new Step("contribute-some", "some", ContributeScreen::new),
            new Step("contribute-all", "all", ContributeScreen::new),
            new Step("contribute-give", "give", ContributeScreen::new),
            new Step("death-window-give", "give", DonationOptionsScreen::new),
            new Step("rewards-some", "some", CommunityRewardsScreen::new),
            new Step("rewards-all-give", "give-all", CommunityRewardsScreen::new));

    private static List<Step> queue = List.of();
    private static int ticks;
    private static int waitTicks;
    private static String grabName;

    private ContributePreviewCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.getDispatcher().register(Commands.literal("dungeontrain-debug-contribute")
                .then(Commands.argument("state", StringArgumentType.word()).executes(ctx -> {
                    String state = StringArgumentType.getString(ctx, "state");
                    if (!apply(state)) return 0;
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ContributeScreen(null)));
                    return 1;
                })));
    }

    /** Set the made-up rewards state; false for an unknown name. */
    private static boolean apply(String state) {
        long now = System.currentTimeMillis();
        CommunityRewards.devForceGive = state.startsWith("give");
        switch (state) {
            case "none" -> LiveRewards.preview(LiveRewards.NONE);
            case "some", "give" -> LiveRewards.preview(new LiveRewards(List.of(
                    new LiveRewards.Reward(LiveRewards.HD_STREAMS, now + 21 * DAY, "Ash", List.of()),
                    new LiveRewards.Reward(LiveRewards.REPLAYS_KEPT, now + 15 * DAY, "Moth", List.of("Kestrel")))));
            case "all", "give-all" -> LiveRewards.preview(new LiveRewards(List.of(
                    new LiveRewards.Reward(LiveRewards.QUICKER_STREAMS, now + 26 * DAY, "Kestrel", List.of("Moth")),
                    new LiveRewards.Reward(LiveRewards.TWO_STREAMS, now + 20 * DAY, "Rook", List.of()),
                    new LiveRewards.Reward(LiveRewards.VOICE_ON_STREAM, now + 25 * DAY, "Ash", List.of()),
                    new LiveRewards.Reward(LiveRewards.HD_STREAMS, now + 12 * DAY, "Wren", List.of()),
                    new LiveRewards.Reward(LiveRewards.REPLAYS_KEPT, now + 9 * DAY, "Moth", List.of()))));
            case "relay" -> LiveRewards.preview(null);
            default -> { return false; }
        }
        return true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!DungeonTrain.isDevBuild()) return;
        Minecraft mc = Minecraft.getInstance();
        if (queue.isEmpty()) {
            if (!(mc.screen instanceof TitleScreen)) return;
            Path flag = mc.gameDirectory.toPath().resolve(FLAG_FILE);
            if (!Files.isRegularFile(flag)) return;
            // Shoot what players see: wait (up to 15 s) for the relay's links, which carry the tiers.
            games.brennan.dungeontrain.client.links.OfficialLinks.ensureFetched();
            if (PaymentLinks.tiersAvailable() == false && ++waitTicks < 300) return;
            try {
                Files.deleteIfExists(flag);
            } catch (java.io.IOException e) {
                LOGGER.warn("[DungeonTrain] contribute preview flag not removed: {}", e.toString());
            }
            LOGGER.info("[DungeonTrain] contribute preview sweep: {} steps", SWEEP.size());
            queue = new ArrayList<>(SWEEP);
            begin(mc);
            return;
        }
        if (++ticks == SETTLE_TICKS) grabName = queue.get(0).name() + ".png";
    }

    private static void begin(Minecraft mc) {
        Step s = queue.get(0);
        apply(s.state());
        mc.setScreen(s.screen().apply(new TitleScreen()));
        ticks = 0;
    }

    /** Shots are taken after a full frame so the screen is on the buffer, not mid-draw. */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        String name = grabName;
        if (name == null) return;
        grabName = null;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                msg -> LOGGER.info("[DungeonTrain] contribute preview {} -> {}", name, msg.getString()));
        queue.remove(0);
        if (queue.isEmpty()) {
            apply("relay");
            CommunityRewards.devForceGive = false;
            mc.setScreen(new TitleScreen());
            LOGGER.info("[DungeonTrain] contribute preview sweep done");
        } else {
            begin(mc);
        }
    }
}
