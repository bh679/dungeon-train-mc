package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.credits.DiscordAccountState;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig.NameTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.ClientHooks;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * The streamer's name, top-middle in white on a translucent black pill, drawn into the broadcast
 * picture only.
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
    static final int TEXT = 0xFFFFFFFF;
    /** Translucent black: the picture shows through, the white name reads on any sky. */
    static final int PILL = 0xB0000000;
    /** GUI pixels between the name and the pill's ends / top and bottom. */
    static final int PAD_X = 6;
    static final int PAD_Y = 3;
    /** Glyph height in GUI pixels, descenders and shadow included. */
    static final int GLYPH_H = 8;

    private StreamNameTag() {}

    /** The tag as it applies: Kid mode never puts a name on the stream. */
    public static NameTag effective(NameTag chosen, boolean kid) {
        return kid ? NameTag.NONE : chosen;
    }

    /** The text to show, or "" for nothing. Discord never falls back to the Minecraft name. */
    public static String text(NameTag tag, String minecraftName, boolean discordLinked, @Nullable String discordName) {
        return switch (tag) {
            case MINECRAFT -> minecraftName == null ? "" : minecraftName.strip();
            case DISCORD -> discordLinked && discordName != null ? discordName.strip() : "";
            case NONE -> "";
        };
    }

    /** This client's tag text now, or "". */
    static String currentText() {
        NameTag tag = effective(LiveFeedClientConfig.streamNameTag(), ClientDisplayConfig.isKidMode());
        if (tag == NameTag.NONE) return "";
        CommunityLinkClient.Status discord = DiscordAccountState.status();
        return text(tag, Minecraft.getInstance().getUser().getName(),
            discord != null && discord.linked(), discord == null ? null : discord.discordName());
    }

    /** Stream start: make sure a Discord tag has a name to show (the cache is filled lazily otherwise). */
    static void prepare() {
        if (effective(LiveFeedClientConfig.streamNameTag(), ClientDisplayConfig.isKidMode()) == NameTag.DISCORD) {
            DiscordAccountState.refresh(() -> { });
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
            draw(graphics, mc.font, name, w, scale);
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            main.bindWrite(true);
        }
    }

    /**
     * The pill in target pixels (so its round ends are smooth at stream resolution, not a GUI-pixel
     * staircase), then the name over it in GUI pixels, centred on the same point.
     */
    private static void draw(GuiGraphics graphics, Font font, String name, int targetW, float scale) {
        int textW = font.width(name);
        int pillW = Math.round((textW + 2 * PAD_X) * scale);
        int pillH = Math.round((GLYPH_H + 2 * PAD_Y) * scale);
        int pillX = (targetW - pillW) / 2;
        int pillY = Math.round(TOP_OFFSET * scale);
        for (int row = 0; row < pillH; row++) {
            int inset = pillInset(row, pillH);
            graphics.fill(pillX + inset, pillY + row, pillX + pillW - inset, pillY + row + 1, PILL);
        }
        graphics.pose().pushPose();
        graphics.pose().translate((targetW - textW * scale) / 2f, pillY + PAD_Y * scale, 0f);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(font, name, 0, 0, TEXT, true);
        graphics.pose().popPose();
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
