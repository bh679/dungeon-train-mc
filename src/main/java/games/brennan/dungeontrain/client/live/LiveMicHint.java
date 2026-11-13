package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.ClientOptionsTab;
import games.brennan.dungeontrain.client.live.audio.LiveMic;
import games.brennan.dungeontrain.client.live.audio.MicCapture;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * The mic line said at go-live, after "N people watching" ({@link LiveStreamController}). A stream
 * only carries a mic track when Microphone and Stream game sound were on as it started, so a player
 * who goes live with it off hears where to turn it on for the next stream. A mic that was wanted but
 * found no device says so here too, not only when the mic key is pressed.
 */
public final class LiveMicHint {

    /** Chat-click action behind "[Turn this reminder off]". */
    static final String OFF_ACTION = "mic-hint-off";

    public enum Kind { NONE, MIC_OFF, NO_DEVICE }

    private LiveMicHint() {}

    /**
     * Which line, if any. {@code micTrack}: this stream carries one; {@code barred}: the relay has the
     * mic off for this streamer (its own line); {@code hintOn}: the reminder is wanted. A mic that is
     * on but missing for another reason (unapproved mods, the sound restart) already has its own line.
     */
    static Kind of(boolean streamAudio, boolean micOption, boolean micTrack, boolean barred,
                   boolean noDevice, boolean hintOn) {
        if (micTrack || barred) return Kind.NONE;
        if (streamAudio && micOption) return noDevice ? Kind.NO_DEVICE : Kind.NONE;
        return hintOn ? Kind.MIC_OFF : Kind.NONE;
    }

    /** The line for the stream that just went live, or null for none. Render thread. */
    @Nullable
    static Component line() {
        Kind kind = of(LiveFeedClientConfig.streamAudio(), LiveFeedClientConfig.micSupport(), LiveMic.enabled(),
            LiveMic.barred(), MicCapture.devices().isEmpty(), LiveFeedClientConfig.micHint());
        return switch (kind) {
            case NONE -> null;
            case NO_DEVICE -> Component.translatable("chat.dungeontrain.live.mic_no_device").withStyle(ChatFormatting.RED);
            case MIC_OFF -> micOffLine();
        };
    }

    private static Component micOffLine() {
        MutableComponent off = Component.literal("[").append(Component.translatable("chat.dungeontrain.twitch.hint_turn_off")).append("]")
            .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withClickEvent(ChatClick.event(OFF_ACTION)));
        return Component.translatable("chat.dungeontrain.live.mic_off_hint",
                LiveOptionsCommand.pathLink("chat.dungeontrain.live.mic_off_hint.path", ClientOptionsTab.STREAM, ClientOptionsTab.Row.MIC_SUPPORT))
            .withStyle(ChatFormatting.GRAY)
            .append(" ")
            .append(off);
    }

    /** Wired from {@code DungeonTrainClient.clientSetup}, beside the Options links. */
    public static void registerClicks() {
        ChatClickActions.register(OFF_ACTION, args -> {
            LiveFeedClientConfig.setMicHint(false);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(
                Component.translatable("chat.dungeontrain.live.mic_hint_off").withStyle(ChatFormatting.GRAY), false);
        });
    }
}
