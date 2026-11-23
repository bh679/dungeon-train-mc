package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.client.HalfHeartContainer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the last heart container as half a heart when max health is odd (the Tank perk adds
 * half a heart per rank), instead of vanilla's whole empty heart. See {@link HalfHeartContainer}.
 *
 * <p>Vanilla's loop draws one container per heart, from the highest index down, so a counter set
 * at the head of {@code renderHearts} tells each wrapped container draw which heart it is.
 */
@Mixin(Gui.class)
public abstract class GuiHalfHeartContainerMixin {

    private static final String RENDER_HEARTS =
        "renderHearts(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V";
    private static final int HEART_SIZE = 9;
    private static final int HALF_WIDTH = 5;

    @Unique
    private int dungeontrain$nextHeartIndex = -1;
    @Unique
    private int dungeontrain$halfHeartIndex = HalfHeartContainer.NONE;

    @Inject(method = RENDER_HEARTS, at = @At("HEAD"))
    private void dungeontrain$startHearts(GuiGraphics graphics, Player player, int x, int y, int height,
                                          int offsetHeartIndex, float maxHealth, int currentHealth,
                                          int displayHealth, int absorptionAmount, boolean renderHighlight,
                                          CallbackInfo ci) {
        int containers = HalfHeartContainer.containers(maxHealth);
        dungeontrain$nextHeartIndex = containers + HalfHeartContainer.containers(absorptionAmount) - 1;
        dungeontrain$halfHeartIndex = HalfHeartContainer.halfIndex(maxHealth);
    }

    @WrapOperation(method = RENDER_HEARTS,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;renderHeart(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Gui$HeartType;IIZZZ)V",
            ordinal = 0))
    private void dungeontrain$halfContainer(Gui gui, GuiGraphics graphics, Gui.HeartType type, int x, int y,
                                            boolean hardcore, boolean highlight, boolean half,
                                            Operation<Void> original) {
        int index = dungeontrain$nextHeartIndex--;
        if (index != dungeontrain$halfHeartIndex || type != Gui.HeartType.CONTAINER) {
            original.call(gui, graphics, type, x, y, hardcore, highlight, half);
            return;
        }
        RenderSystem.enableBlend();
        graphics.blitSprite(type.getSprite(hardcore, half, highlight),
            HEART_SIZE, HEART_SIZE, 0, 0, x, y, HALF_WIDTH, HEART_SIZE);
        RenderSystem.disableBlend();
    }
}
