package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import com.mojang.blaze3d.systems.RenderSystem;
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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One label above the chat lines saying Live chat is translated, whenever a translated line is on screen
 * ({@link LiveChatBridge} reports them), whoever translated it. While any of them is Google's work it is
 * Google's own unaltered "powered by Google Translate" badge instead, as its rules require: drawn above other
 * HUD panels and held on screen, as the credit must never be covered or missing. A line is recognised by the tick it entered chat, which vanilla keeps through re-wrapping. In a
 * closed chat the label fades with the newest translated line; in an open one it follows the scroll position.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveChatTranslatedPill {

    /** Vanilla: a closed chat's line is gone after this many ticks, and the chat's bottom sits this far up. */
    private static final int LINE_LIFE_TICKS = 200;
    private static final int CHAT_BOTTOM_OFFSET = 40;
    private static final int CHAT_LEFT = 4;
    private static final int PAD_X = 3;
    private static final int PAD_Y = 1;
    private static final int GAP = 2;
    private static final int TEXT_RGB = 0xD8D8D8;
    private static final float BACKDROP_OPACITY = 0.72f;
    /** Google's badge, byte for byte as Google ships it (white-regular@3x), and how large to draw it. */
    private static final ResourceLocation GOOGLE_BADGE = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/live/google_translate_badge.png");
    private static final int BADGE_TEXTURE_W = 528;
    private static final int BADGE_TEXTURE_H = 48;
    private static final int BADGE_H = 12;
    /** Ticks of the translated lines remembered: more than chat could show at once. */
    private static final int TICKS_KEPT = 256;
    /** Below this, Minecraft's text renderer draws nothing recognisable anyway. */
    private static final float MIN_ALPHA = 0.03f;

    /** The relay's id for who is translating this room now. */
    @Nullable private static String translator;
    /** The tick each translated line entered chat, oldest first, to whether Google made it. Client thread only, as is rendering. */
    private static final Map<Integer, Boolean> TRANSLATED_TICKS = new LinkedHashMap<>();

    private LiveChatTranslatedPill() {}

    /** Each chat poll: who the relay says is translating now, or null. */
    static void setTranslator(@Nullable String id) {
        translator = id;
    }

    /** A translated line, the reader's to read, stands in chat with this added-tick. */
    static void noteTranslatedLine(int tick) {
        boolean google = LiveChatTranslations.GOOGLE.equals(translator);
        TRANSLATED_TICKS.merge(tick, google, Boolean::logicalOr);
        if (TRANSLATED_TICKS.size() > TICKS_KEPT) TRANSLATED_TICKS.remove(TRANSLATED_TICKS.keySet().iterator().next());
    }

    /** Off Live chat: nothing to name. */
    static void reset() {
        translator = null;
        TRANSLATED_TICKS.clear();
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

    /** The added-tick of the newest visible line that is translated, or null when none on screen is. */
    @Nullable
    static Integer newestTranslated(List<Integer> visibleNewestFirst, Set<Integer> translatedTicks) {
        for (int added : visibleNewestFirst) {
            if (translatedTicks.contains(added)) return added;
        }
        return null;
    }

    /** The label's top edge: above the chat lines when there is room, else held at the top of the screen. */
    static int labelTop(int bottom, int height) {
        return Math.max(0, bottom - height);
    }

    /** The badge's width at {@code height}, in its own proportions: Google's marks must not be distorted. */
    static int badgeWidth(int height) {
        return Math.round(height * (float) BADGE_TEXTURE_W / BADGE_TEXTURE_H);
    }

    /** Whether any visible line is Google's work: one such line is enough to owe the credit. */
    static boolean googleOnScreen(List<Integer> visible, Map<Integer, Boolean> translatedTicks) {
        return visible.stream().anyMatch(tick -> Boolean.TRUE.equals(translatedTicks.get(tick)));
    }

    /** After the whole HUD, other mods' panels included: Google's credit must never be covered. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderHud(RenderGuiEvent.Post event) {
        render(event.getGuiGraphics());
    }

    private static void render(GuiGraphics graphics) {
        if (TRANSLATED_TICKS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccessor access = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = access.dungeontrain$getTrimmedMessages();
        if (lines.isEmpty()) return;

        int guiTicks = mc.gui.getGuiTicks();
        boolean open = chat.isChatFocused();
        int scroll = open ? Mth.clamp(access.dungeontrain$getChatScrollbarPos(), 0, lines.size()) : 0;
        List<Integer> page = lines.subList(scroll, Math.min(lines.size(), scroll + chat.getLinesPerPage())).stream().map(GuiMessage.Line::addedTime).toList();
        int shown = open ? page.size() : linesStillShowing(page, guiTicks, page.size());
        List<Integer> visible = page.subList(0, shown);
        Integer translated = newestTranslated(visible, TRANSLATED_TICKS.keySet());
        if (translated == null) return;
        boolean google = googleOnScreen(visible, TRANSLATED_TICKS);
        float alpha = open ? 1f : fade(guiTicks - translated);
        if (alpha < MIN_ALPHA) return;

        float scale = (float) chat.getScale();
        int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
        int chatBottom = Mth.floor((graphics.guiHeight() - CHAT_BOTTOM_OFFSET) / scale);
        int bottom = chatBottom - shown * lineHeight - GAP;

        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1f);
        if (google) {
            // HUD layers stack higher in depth one after another: only drawing last, untested, puts this on top.
            graphics.flush();
            RenderSystem.disableDepthTest();
            drawGoogleBadge(graphics, bottom, alpha);
            graphics.flush();
            RenderSystem.enableDepthTest();
        } else {
            drawTranslatedLabel(graphics, mc.font, bottom, alpha);
        }
        graphics.pose().popPose();
    }

    private static void drawTranslatedLabel(GuiGraphics graphics, Font font, int bottom, float alpha) {
        Component text = LiveChatTranslations.translatedLabel();
        int height = font.lineHeight + 2 * PAD_Y;
        int top = labelTop(bottom, height);
        graphics.fill(CHAT_LEFT, top, CHAT_LEFT + font.width(text) + 2 * PAD_X, top + height, argb(alpha * BACKDROP_OPACITY, 0x000000));
        graphics.drawString(font, text, CHAT_LEFT + PAD_X, top + PAD_Y, argb(alpha, TEXT_RGB), false);
    }

    private static void drawGoogleBadge(GuiGraphics graphics, int bottom, float alpha) {
        int height = BADGE_H + 2 * PAD_Y;
        int width = badgeWidth(BADGE_H);
        int top = labelTop(bottom, height);
        graphics.fill(CHAT_LEFT, top, CHAT_LEFT + width + 2 * PAD_X, top + height, argb(alpha * BACKDROP_OPACITY, 0x000000));
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        graphics.blit(GOOGLE_BADGE, CHAT_LEFT + PAD_X, top + PAD_Y, width, BADGE_H, 0f, 0f, BADGE_TEXTURE_W, BADGE_TEXTURE_H, BADGE_TEXTURE_W, BADGE_TEXTURE_H);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private static int argb(float alpha, int rgb) {
        return Mth.clamp((int) (alpha * 255f), 0, 255) << 24 | rgb;
    }
}
