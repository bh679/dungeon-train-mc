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
 * The mic line said at go-live, after "N people watching" ({@link LiveStreamController}). A player
 * who goes live with the mic off hears where to turn it on: on a stream with sound, switching it on
 * adds a mic track to this stream ({@code LiveMic.Toggle.JOINING}). A stream without sound cannot
 * gain one, so it says nothing there. A mic that was wanted but found no device says so here too,
 * not only when the mic key is pressed.
 */
public final class LiveMicHint {

    /** Chat-click action behind "[Turn this reminder off]". */
    static final String OFF_ACTION = "mic-hint-off";

    public enum Kind { NONE, MIC_OFF, NO_DEVICE }

    private LiveMicHint() {}

    /**
     * Which line, if any. {@code streamSound}: the stream on air carries sound (a mic needs it);
     * {@code micTrack}: it carries a mic track; {@code barred}: the relay has the mic off for this
     * streamer (its own line); {@code hintOn}: the reminder is wanted. A mic that is on but missing for
     * another reason (unapproved mods, the sound restart) already has its own line.
     */
    static Kind of(boolean streamSound, boolean micOption, boolean micTrack, boolean barred,
                   boolean noDevice, boolean hintOn) {
        if (!streamSound || micTrack || barred) return Kind.NONE;
        if (micOption) return noDevice ? Kind.NO_DEVICE : Kind.NONE;
        return hintOn ? Kind.MIC_OFF : Kind.NONE;
    }

    /** The line for the stream that just went live, or null for none. Render thread. */
    @Nullable
    static Component line(boolean streamSound) {
        Kind kind = of(streamSound, LiveFeedClientConfig.micSupport(), LiveMic.enabled(),
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
