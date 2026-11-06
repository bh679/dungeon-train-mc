package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * "Translated by X": one label above the chat lines naming the service translating Live chat, once this
 * reader has been shown a translation and for as long as the relay names one ({@link LiveChatBridge} feeds
 * both). It sits on the top visible line and fades with the newest, so it leaves with the closed chat;
 * with chat open it stays. Nothing is drawn with no chat lines on screen.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveChatTranslatedPill {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_chat_translated_pill");
    /** Vanilla: a closed chat's line is gone after this many ticks, and the chat's bottom sits this far up. */
    private static final int LINE_LIFE_TICKS = 200;
    private static final int CHAT_BOTTOM_OFFSET = 40;
    private static final int CHAT_LEFT = 4;
    private static final int PAD_X = 3;
    private static final int PAD_Y = 1;
    private static final int GAP = 2;
    private static final int TEXT_RGB = 0xD8D8D8;
    private static final float BACKDROP_OPACITY = 0.72f;
    /** Below this, Minecraft's text renderer draws nothing recognisable anyway. */
    private static final float MIN_ALPHA = 0.03f;

    /** The relay's id for who is translating this room now; read on the render thread, set on the client thread. */
    @Nullable private static volatile String translator;
    private static volatile boolean shownToReader;

    private LiveChatTranslatedPill() {}

    /** Each chat poll: who the relay says is translating now, or null. */
    static void setTranslator(@Nullable String id) {
        translator = id;
    }

    /** A translation was just shown to this reader. */
    static void noteShown() {
        shownToReader = true;
    }

    /** Off Live chat: nothing to name. */
    static void reset() {
        translator = null;
        shownToReader = false;
    }

    /** Vanilla's fade for a closed chat's line this many ticks old: 1 until its last 20 ticks, then to 0. */
    static float fade(int ageTicks) {
        float left = Mth.clamp((1f - ageTicks / (float) LINE_LIFE_TICKS) * 10f, 0f, 1f);
        return left * left;
    }

    /** How many of the lines, newest first, a closed chat still shows at {@code guiTicks}. */
    static int linesStillShowing(List<Integer> addedTimesNewestFirst, int guiTicks, int maxLines) {
        int n = 0;
        for (int added : addedTimesNewestFirst) {
            if (n >= maxLines || guiTicks - added >= LINE_LIFE_TICKS) break;
            n++;
        }
        return n;
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, LAYER, (graphics, deltaTracker) -> render(graphics));
    }

    private static void render(GuiGraphics graphics) {
        if (!shownToReader) return;
        Component text = LiveChatTranslations.translatedBy(translator);
        if (text == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
        ChatComponent chat = mc.gui.getChat();
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).dungeontrain$getTrimmedMessages();
        if (lines.isEmpty()) return;

        int guiTicks = mc.gui.getGuiTicks();
        boolean open = chat.isChatFocused();
        int shown = open
            ? Math.min(lines.size(), chat.getLinesPerPage())
            : linesStillShowing(lines.stream().limit(chat.getLinesPerPage()).map(GuiMessage.Line::addedTime).toList(), guiTicks, chat.getLinesPerPage());
        if (shown == 0) return;
        float alpha = open ? 1f : fade(guiTicks - lines.get(0).addedTime());
        if (alpha < MIN_ALPHA) return;

        float scale = (float) chat.getScale();
        int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
        int chatBottom = Mth.floor((graphics.guiHeight() - CHAT_BOTTOM_OFFSET) / scale);
        Font font = mc.font;
        int height = font.lineHeight + 2 * PAD_Y;
        int bottom = chatBottom - shown * lineHeight - GAP;
        if (bottom - height < 0) return;

        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1f);
        int width = font.width(text) + 2 * PAD_X;
        graphics.fill(CHAT_LEFT, bottom - height, CHAT_LEFT + width, bottom, argb(alpha * BACKDROP_OPACITY, 0x000000));
        graphics.drawString(font, text, CHAT_LEFT + PAD_X, bottom - height + PAD_Y, argb(alpha, TEXT_RGB), false);
        graphics.pose().popPose();
    }

    private static int argb(float alpha, int rgb) {
        return Mth.clamp((int) (alpha * 255f), 0, 255) << 24 | rgb;
    }
}
