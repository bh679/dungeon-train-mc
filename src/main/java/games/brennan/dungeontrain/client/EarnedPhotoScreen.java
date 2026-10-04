package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shows the photo that earned an advancement, as a print with the advancement's title beneath it. Opened
 * by clicking the earned advancement ({@link EarnedPhotos#tryOpen}), drawn over the advancements screen;
 * a click anywhere or Esc puts it away. A screen of DT's own — not Exposure's photograph screen — so the burn-after-viewing and
 * Tribute hooks that watch that screen never see it.
 */
public final class EarnedPhotoScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "earned_photo/view");
    private static final int PAPER = 0xFFF2EEE4;
    private static final int BORDER = 8;
    private static final int CAPTION = 18;
    /** Depth the overlay draws at: above the advancements screen's item icons and tooltips. */
    private static final float OVERLAY_Z = 1000F;

    private final Screen parent;
    private final Path file;
    private DynamicTexture texture;
    private int imageWidth;
    private int imageHeight;

    public EarnedPhotoScreen(Screen parent, Component title, Path file) {
        super(title);
        this.parent = parent;
        this.file = file;
    }

    @Override
    protected void init() {
        // Drawn over the advancements screen: keep it laid out for the current window size.
        if (parent != null && (parent.width != width || parent.height != height)) {
            parent.resize(minecraft, width, height);
        }
        if (texture != null) return;
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            texture = new DynamicTexture(image);
            minecraft.getTextureManager().register(TEXTURE, texture);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't open the advancement photo {}: {}", file, e.toString());
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (texture == null || imageWidth <= 0 || imageHeight <= 0) return;
        int maxSide = Math.min(width, height) - 2 * BORDER - CAPTION - 40;
        float scale = Math.min((float) maxSide / imageWidth, (float) maxSide / imageHeight);
        int w = Math.max(1, Math.round(imageWidth * scale));
        int h = Math.max(1, Math.round(imageHeight * scale));
        int x = (width - w) / 2;
        int y = (height - h - CAPTION) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.fill(x - BORDER, y - BORDER, x + w + BORDER, y + h + BORDER + CAPTION, PAPER);
        g.blit(TEXTURE, x, y, w, h, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        g.drawString(font, title, (width - font.width(title)) / 2, y + h + 6, 0xFF3A3530, false);
        g.pose().popPose();
    }

    /** The advancements screen stays visible underneath, dimmed — not the blurred world. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            parent.render(g, -1, -1, partialTick);
        } else {
            super.renderBackground(g, mouseX, mouseY, partialTick);
        }
        // Above everything the advancements screen drew — its item icons sit at a raised depth.
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.fill(0, 0, width, height, 0xA0000000);
        g.pose().popPose();
    }

    /** A click anywhere puts the print away. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEXTURE);
            texture = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
