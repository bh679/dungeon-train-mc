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
 * The streamer's name, bottom-middle just above the hotbar, drawn into the broadcast picture only.
 *
 * <p>It goes into the small capture target after the main frame is blitted down and before it is
 * read back ({@link LiveFrameGrabber}), and into the Options preview the same way
 * ({@link StreamPreview}) — so viewers and the preview see it, the streamer's own screen never does,
 * and it is there whichever capture point the Stream switches chose. Sized in the stream's own GUI
 * pixels so it sits where the hotbar does in the picture.</p>
 *
 * <p>Which name: {@link LiveFeedClientConfig#streamNameTag()}. Kid mode shows none. A Discord tag
 * with no linked Discord name shows nothing rather than falling back to the Minecraft name.</p>
 */
public final class StreamNameTag {

    /** GUI pixels from the bottom of the picture to the bottom of the tag: just over the armour row. */
    static final int BOTTOM_OFFSET = 50;
    static final int PAD_X = 3;
    static final int PAD_Y = 2;
    static final int BACKING = 0x80000000;
    static final int TEXT = 0xFFFFFFFF;

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
            graphics.pose().pushPose();
            graphics.pose().scale(scale, scale, 1f);
            draw(graphics, mc.font, name, Math.round(w / scale), Math.round(h / scale));
            graphics.pose().popPose();
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            main.bindWrite(true);
        }
    }

    private static void draw(GuiGraphics graphics, Font font, String name, int guiW, int guiH) {
        int textW = font.width(name);
        int x = (guiW - textW) / 2;
        int bottom = guiH - BOTTOM_OFFSET;
        int y = bottom - PAD_Y - font.lineHeight + 1;
        graphics.fill(x - PAD_X, y - PAD_Y, x + textW + PAD_X, bottom, BACKING);
        graphics.drawString(font, name, x, y, TEXT, true);
    }
}
