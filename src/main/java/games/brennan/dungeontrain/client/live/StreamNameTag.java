package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.credits.DiscordAccountState;
import games.brennan.dungeontrain.client.twitch.TwitchAccountState;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig.NameTag;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.ClientHooks;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * "<b>Name</b> is live", top-middle in black on a black-outlined white pill with a pulsing green
 * dot before it ("… is live with chat disabled" when the streamer has Live chat off), drawn into the
 * broadcast picture only.
 *
 * <p>It goes into the small capture target after the main frame is blitted down and before it is
 * read back ({@link LiveFrameGrabber}), and into the Options preview the same way
 * ({@link StreamPreview}) — so viewers and the preview see it, the streamer's own screen never does,
 * and it is there on every frame (menus open, GUI off) until the streamer sets it to None. Sized in
 * the stream's own GUI pixels so it matches the HUD viewers see.</p>
 *
 * <p>Which name: {@link LiveFeedClientConfig#streamNameTag()}. Kid mode shows none. A Discord tag
 * with no linked Discord name shows nothing rather than falling back to the Minecraft name.</p>
 */
public final class StreamNameTag {

    /** GUI pixels from the top of the picture to the top of the pill. */
    static final int TOP_OFFSET = 4;
    static final String LABEL_KEY = "gui.dungeontrain.live.name_tag";
    static final String LABEL_CHAT_OFF_KEY = "gui.dungeontrain.live.name_tag.chat_off";
    static final int TEXT = 0xFF000000;
    static final int PILL = 0xFFFFFFFF;
    static final int OUTLINE = 0xFF000000;
    /** The live dot's colour, without alpha — the pulse supplies that. */
    static final int DOT_RGB = 0x22C55E;
    /** GUI pixels between the dot+name group and the pill's ends / top and bottom. */
    static final int PAD_X = 6;
    static final int PAD_Y = 3;
    /** Glyph height in GUI pixels, descenders included. */
    static final int GLYPH_H = 8;
    /** The dot's diameter and its gap to the name, in GUI pixels. */
    static final int DOT_SIZE = 5;
    static final int DOT_GAP = 4;
    /** One full fade out and back in. */
    static final long PULSE_MS = 1200;
    /** The dot's dimmest alpha, out of 1; it peaks at fully opaque. */
    static final double PULSE_MIN = 0.35;

    private StreamNameTag() {}

    /** The tag as it applies: Kid mode never puts a name on the stream. */
    public static NameTag effective(NameTag chosen, boolean kid) {
        return kid ? NameTag.NONE : chosen;
    }

    /** The text to show, or "" for nothing. Discord never falls back to the Minecraft name. */
    public static String text(NameTag tag, String minecraftName, boolean discordLinked, @Nullable String discordName) {
        return text(tag, minecraftName, discordLinked, discordName, null);
    }

    /**
     * The text to show, or "" for nothing; with a linked Twitch channel ({@code twitchLogin}) the handle
     * follows the name — " · twitch.tv/x" — so every viewer, Twitch's included, can find the streamer.
     * A tag of NONE stays empty: no name means no handle either.
     */
    public static String text(NameTag tag, String minecraftName, boolean discordLinked, @Nullable String discordName,
                              @Nullable String twitchLogin) {
        String name = switch (tag) {
            case MINECRAFT -> minecraftName == null ? "" : minecraftName.strip();
            case DISCORD -> discordLinked && discordName != null ? discordName.strip() : "";
            case NONE -> "";
        };
        if (name.isEmpty() || twitchLogin == null || !twitchLogin.matches("[A-Za-z0-9_]{3,25}")) return name;
        return name + " · twitch.tv/" + twitchLogin.toLowerCase();
    }

    /** This client's tag text now, or "". */
    static String currentText() {
        NameTag tag = effective(LiveFeedClientConfig.streamNameTag(), ClientDisplayConfig.isKidMode());
        if (tag == NameTag.NONE) return "";
        CommunityLinkClient.Status discord = DiscordAccountState.status();
        return text(tag, Minecraft.getInstance().getUser().getName(),
            discord != null && discord.linked(), discord == null ? null : discord.discordName(),
            TwitchAccountState.linkedLogin());
    }

    /** Stream start: make sure a Discord tag has a name to show, and the Twitch handle is known (both caches fill lazily otherwise). */
    static void prepare() {
        if (effective(LiveFeedClientConfig.streamNameTag(), ClientDisplayConfig.isKidMode()) == NameTag.DISCORD) {
            DiscordAccountState.refresh(() -> { });
        }
        if (effective(LiveFeedClientConfig.streamNameTag(), ClientDisplayConfig.isKidMode()) != NameTag.NONE) {
            TwitchAccountState.refresh(() -> { });
        }
    }

    /**
     * Render thread: draw the tag into {@code target} (already holding this frame), then rebind the
     * main target. Projection and model-view are set here and restored, as the call can land inside
     * or after the GUI pass.
     */
    static void drawInto(RenderTarget target) {
        String name = currentText();
        if (name.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width <= 0) return;
        int w = target.width;
        int h = target.height;
        float scale = (float) (mc.getWindow().getGuiScale() * w / main.width);
        if (scale <= 0f) return;

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, ClientHooks.getGuiFarPlane()),
                VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            target.bindWrite(true);
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw(graphics, mc.font, label(name, chatOff(LiveFeedClientConfig.liveChat(), ClientDisplayConfig.isKidMode())),
                w, scale);
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            main.bindWrite(true);
        }
    }

    /**
     * The outlined pill and the dot in target pixels (so their curves are smooth at stream
     * resolution, not a GUI-pixel staircase), then the name in GUI pixels.
     */
    private static void draw(GuiGraphics graphics, Font font, Component label, int targetW, float scale) {
        int textW = font.width(label) - 1; // drop the trailing 1px advance so the group centres
        float groupW = (DOT_SIZE + DOT_GAP + textW) * scale;
        int pillW = Math.round(groupW + 2 * PAD_X * scale);
        int pillH = Math.round((GLYPH_H + 2 * PAD_Y) * scale);
        int pillX = (targetW - pillW) / 2;
        int pillY = Math.round(TOP_OFFSET * scale);
        int line = Math.max(1, Math.round(scale));
        pill(graphics, pillX, pillY, pillW, pillH, OUTLINE);
        pill(graphics, pillX + line, pillY + line, pillW - 2 * line, pillH - 2 * line, PILL);

        float groupX = (targetW - groupW) / 2f;
        int dot = Math.round(DOT_SIZE * scale);
        int dotY = pillY + (pillH - dot) / 2;
        pill(graphics, Math.round(groupX), dotY, dot, dot, dotColor(System.currentTimeMillis()));

        graphics.pose().pushPose();
        graphics.pose().translate(groupX + (DOT_SIZE + DOT_GAP) * scale, pillY + PAD_Y * scale, 0f);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(font, label, 0, 0, TEXT, false);
        graphics.pose().popPose();
    }

    /**
     * "<b>name</b> is live", or "<b>name</b> is live with chat disabled" when {@code chatOff}, in the
     * streamer's language. Whole sentences per key so each language keeps its own word order.
     */
    static Component label(String name, boolean chatOff) {
        return Component.translatable(chatOff ? LABEL_CHAT_OFF_KEY : LABEL_KEY,
            Component.literal(name).withStyle(ChatFormatting.BOLD));
    }

    /**
     * Viewers' cue that the streamer won't see their messages: Live chat (the stream's Discord chat
     * mirrored into the game) is off, or Kid mode forces it off — the rule {@link LiveChatBridge} uses.
     */
    static boolean chatOff(boolean liveChat, boolean kid) {
        return !liveChat || kid;
    }

    /** A {@code w}×{@code h} pill at ({@code x},{@code y}); with {@code w == h} it is a disc. */
    private static void pill(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        for (int row = 0; row < h; row++) {
            int inset = pillInset(row, h);
            graphics.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    /** The live dot's ARGB at {@code nowMs}: green, its alpha pulsing on {@link #PULSE_MS}. */
    static int dotColor(long nowMs) {
        int alpha = (int) Math.round(dotAlpha(nowMs) * 255);
        return alpha << 24 | DOT_RGB;
    }

    /** Smooth sine pulse from fully opaque at the start of each period down to {@link #PULSE_MIN} halfway. */
    static double dotAlpha(long nowMs) {
        double phase = (Math.floorMod(nowMs, PULSE_MS)) / (double) PULSE_MS;
        double wave = (Math.cos(phase * 2 * Math.PI) + 1) / 2; // 1 → 0 → 1
        return PULSE_MIN + (1 - PULSE_MIN) * wave;
    }

    /**
     * How far row {@code row} of a {@code height}-tall pill is cut in from each side: the ends are
     * half-discs of radius {@code height / 2}, so the middle rows run full width and the top and
     * bottom rows are pulled in.
     */
    static int pillInset(int row, int height) {
        double r = height / 2.0;
        double dy = row + 0.5 - r;
        double half = Math.sqrt(Math.max(0.0, r * r - dy * dy));
        return (int) Math.round(r - half);
    }
}
