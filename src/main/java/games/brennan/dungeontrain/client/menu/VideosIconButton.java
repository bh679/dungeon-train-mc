package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The title-screen icon that opens the Videos page; sits in the icon column between Credits and
 * Discord (see {@code TitleScreenCreditsButton}).
 *
 * <p>Two faces, one slot. For most clients it is a red rounded tile with a white play triangle —
 * the shape every platform uses for "video", drawn programmatically like {@link DiscordIconButton}
 * so no texture ships. For a <b>Chinese-language client</b> ({@link ClientLanguage#isChinese()}) it
 * is the Bilibili mark instead ({@code textures/gui/bilibili.png}, the artwork
 * {@link BilibiliIconButton} blits): that is the platform those players watch on, and the Videos
 * page — which lists Bilibili videos and links Brennan's Bilibili channel at the bottom — is where
 * the standalone Bilibili icon the column used to carry for them now leads.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideosIconButton extends Button {

    private static final int RED       = 0xFFE53935;
    private static final int RED_HOVER = 0xFFEF5350;
    private static final int MARK      = 0xFFFFFFFF;

    private static final ResourceLocation BILIBILI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/bilibili.png");
    private static final int BILIBILI_TEX = 32;
    private static final int HOVER_WASH = 0x33FFFFFF;

    public VideosIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int inset = Math.max(1, Math.round(s * 0.08F));

        if (ClientLanguage.isChinese()) {
            // The Bilibili artwork, with the same hover wash BilibiliIconButton uses.
            RenderSystem.enableBlend();
            g.blit(BILIBILI_TEXTURE, x, y, getWidth(), getHeight(), 0.0F, 0.0F,
                    BILIBILI_TEX, BILIBILI_TEX, BILIBILI_TEX, BILIBILI_TEX);
            if (isHoveredOrFocused()) {
                g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), HOVER_WASH);
                g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, HOVER_WASH);
            }
            return;
        }

        // Red tile, corners nipped so it reads as the rounded app icon rather than a square.
        int body = isHoveredOrFocused() ? RED_HOVER : RED;
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);
        PlatformToggleButton.drawPlay(g, x, y, s, MARK);
    }
}
