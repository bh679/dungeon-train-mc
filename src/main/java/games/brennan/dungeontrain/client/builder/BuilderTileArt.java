package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The screenshot behind a builder mode, drawn wherever a mode needs to be shown as a picture.
 *
 * <p>Two places want it — the {@link TrainBuilderScreen} picker tile and the New screen's mode row —
 * and they must fail the same way: the tile art isn't in the repo yet, so a missing PNG has to
 * degrade to a flat slate panel rather than the black-and-magenta checkerboard, which reads as a
 * bug rather than as artwork that hasn't landed.</p>
 *
 * <p>Presence is probed per call site at construction time, not per frame, because screens rebuild
 * their widgets in {@code init()} — which is also what runs on a resource reload, so a texture that
 * appears later is picked up without a restart.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileArt {

    /**
     * Nominal source rect. {@code GuiGraphics.blit} divides u/uWidth by the declared texture size,
     * so passing the same numbers for both samples the whole image whatever its real pixel
     * dimensions are — which matters here because the art is authored as screenshots at whatever
     * resolution the shot was taken.
     */
    private static final int SRC = 16;

    private static final int FALLBACK_BG = 0xFF3A4048;

    private BuilderTileArt() {}

    static ResourceLocation textureFor(BuilderMode mode) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, mode.texturePath());
    }

    /** Whether this mode's PNG is present in the loaded resource packs. */
    static boolean isAvailable(BuilderMode mode) {
        return Minecraft.getInstance().getResourceManager().getResource(textureFor(mode)).isPresent();
    }

    /**
     * Fill {@code (x, y, w, h)} with the mode's art, or the slate fallback when {@code available}
     * is false. Callers pass their own probe result so the lookup isn't repeated every frame.
     */
    static void render(GuiGraphics g, BuilderMode mode, boolean available,
                       int x, int y, int w, int h, float alpha) {
        if (!available) {
            g.fill(x, y, x + w, y + h, FALLBACK_BG);
            return;
        }
        RenderSystem.enableBlend();
        g.setColor(1.0F, 1.0F, 1.0F, alpha);
        g.blit(textureFor(mode), x, y, w, h, 0.0F, 0.0F, SRC, SRC, SRC, SRC);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
