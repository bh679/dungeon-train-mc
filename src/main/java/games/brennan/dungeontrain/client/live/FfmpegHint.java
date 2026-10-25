package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the player why a TV shows the "needs FFmpeg" card instead of a broadcast, once per launch,
 * with a button that installs it ({@link FfmpegInstaller}). Also keeps {@link FfmpegSupport#watch}
 * running from the first tick, so a failed Vista download gets DT's copy even before any TV is seen.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FfmpegHint {

    /** The button's action ({@link ChatClick}): {@code ffmpeg/install}. */
    static final String CLICK_ACTION = "ffmpeg";
    private static final String KEY = "chat.dungeontrain.live.ffmpeg.";
    /** A TV block this close (or a TV in hand) counts as seen. */
    private static final double NEAR_SQ = 16.0 * 16.0;

    private static boolean posted;

    private FfmpegHint() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        FfmpegSupport.watch();
    }

    /** Render thread: a TV drew the "needs FFmpeg" card; {@code tvPos} is null for a TV in hand. */
    static void shown(@Nullable BlockPos tvPos) {
        if (posted || FfmpegInstaller.running()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (tvPos != null && mc.player.distanceToSqr(tvPos.getCenter()) > NEAR_SQ) return;
        posted = true;
        mc.player.displayClientMessage(hint(), false);
    }

    private static Component hint() {
        MutableComponent button = Component.translatable(KEY + "button")
            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(ChatClick.event(CLICK_ACTION, "install"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable(KEY + "button.hover"))));
        return Component.translatable(KEY + "hint").withStyle(ChatFormatting.GOLD)
            .append(" ")
            .append(button);
    }

    private static void say(String key, ChatFormatting colour) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(KEY + key).withStyle(colour), false);
    }

    /** Wire the button and the install outcome lines ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        FfmpegInstaller.setListener(new FfmpegInstaller.Listener() {
            @Override public void installed() { say("ready", ChatFormatting.GREEN); }
            @Override public void failed() { say("failed", ChatFormatting.RED); }
        });
        ChatClickActions.register(CLICK_ACTION, args -> {
            if (args.isEmpty() || !args.get(0).equals("install")) return;
            if (FfmpegInstaller.running()) return;
            if (FfmpegSupport.state() == FfmpegSupport.State.READY) {
                say("ready", ChatFormatting.GREEN);
                return;
            }
            say("installing", ChatFormatting.GRAY);
            FfmpegInstaller.install();
        });
    }
}
