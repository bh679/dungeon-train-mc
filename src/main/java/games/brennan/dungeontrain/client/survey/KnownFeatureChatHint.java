package games.brennan.dungeontrain.client.survey;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.ClientLanguage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Asking in chat for something the game already has: a private line under the message, "You already
 * have this: Trash slot [See how]", which opens the same note as {@code /feedback}
 * ({@link KnownFeatureNoteScreen}). Players ask the dev in chat as often as in the survey.
 *
 * <p>Only the player sees the line, and only once per feature per session, so a conversation about
 * backpacks doesn't repeat it. The AI note is left to the survey: in chat "AI" is mostly about mobs.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class KnownFeatureChatHint {

    /** Chat-click action behind "[See how]"; its one argument is the feature's id. */
    static final String CLICK_ACTION = "known-feature";

    private static final Set<KnownFeature> SAID_THIS_SESSION = EnumSet.noneOf(KnownFeature.class);

    private KnownFeatureChatHint() {}

    @SubscribeEvent
    public static void onChatSent(ClientChatEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.startsWith("/")) return;
        Set<KnownFeature> available = KnownFeatureNotes.available();
        available.remove(KnownFeature.AI);
        available.removeAll(SAID_THIS_SESSION);
        List<KnownFeature> notes = KnownFeatureDetector.detect(-1, msg, ClientLanguage.selected(), available);
        Minecraft mc = Minecraft.getInstance();
        if (notes.isEmpty() || mc.player == null) return;
        SAID_THIS_SESSION.addAll(notes);
        // After the message itself lands, so the hint reads as a reply to it.
        mc.execute(() -> {
            if (mc.player == null) return;
            for (KnownFeature note : notes) mc.player.displayClientMessage(line(note), false);
        });
    }

    private static Component line(KnownFeature note) {
        MutableComponent how = Component.literal("[").append(Component.translatable("chat.dungeontrain.known_feature.see_how")).append("]")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(ChatClick.event(CLICK_ACTION, note.id())));
        return Component.translatable("chat.dungeontrain.known_feature.line", KnownFeatureNotes.title(note))
            .withStyle(ChatFormatting.GRAY)
            .append(" ")
            .append(how);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SAID_THIS_SESSION.clear();
    }

    /** Wired from {@code DungeonTrainClient.clientSetup}, beside the other chat links. */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            if (args.isEmpty()) return;
            for (KnownFeature feature : KnownFeature.values()) {
                if (feature.id().equals(args.get(0))) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new KnownFeatureNoteScreen(mc.screen, List.of(feature)));
                    return;
                }
            }
        });
    }
}
