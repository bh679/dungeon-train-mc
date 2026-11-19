package games.brennan.dungeontrain.mixin.advancementplaques;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.NightCrestPlaque;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Advancement Plaques shows the star-frame advancements as the Night Crest ({@link NightCrestPlaque})
 * instead of its challenge plaque. Advancement Plaques 1.6.8 (1.21.1) draws a plaque in
 * {@code drawPlaque}, which picks the fade/hold times, and its lambda, which blits the plaque from its
 * sheet (the first {@code GuiHelper.blit}), writes the type name and title, plays the sound and blits
 * the appear flash (the second or third blit, by type). Every hook falls through to Plaques' own
 * drawing for any other advancement; {@code defaultRequire 0} keeps a newer Plaques that moved them
 * on its stock plaque rather than failing.
 */
@Mixin(targets = "com.anthonyhilyard.advancementplaques.ui.render.AdvancementPlaque", remap = false)
public abstract class AdvancementPlaqueNightCrestMixin {

    private static final String DRAW = "lambda$drawPlaque$0";
    private static final String BLIT =
        "Lcom/anthonyhilyard/iceberg/util/GuiHelper;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIFFIIII)V";

    @Shadow @Final private AdvancementToast toast;

    @Unique private Boolean dungeontrain$nightCrest;

    @Unique
    private AdvancementHolder dungeontrain$holder() {
        return ((AdvancementToastAccessor) toast).dungeontrain$advancement();
    }

    @Unique
    private boolean dungeontrain$isNightCrest() {
        if (dungeontrain$nightCrest == null) {
            boolean crest;
            try {
                crest = NightCrestPlaque.isFor(dungeontrain$holder());
            } catch (Throwable t) {
                crest = false;
            }
            dungeontrain$nightCrest = crest;
        }
        return dungeontrain$nightCrest;
    }

    /** Held longer: the hold time is the third float {@code drawPlaque} stores (fade in, fade out, hold). */
    @ModifyVariable(method = "drawPlaque", at = @At("STORE"), index = 9)
    private float dungeontrain$holdLonger(float holdMs) {
        return dungeontrain$isNightCrest() ? holdMs * NightCrestPlaque.DURATION_SCALE : holdMs;
    }

    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    private void dungeontrain$plaque(PoseStack pose, int x, int y, int w, int h, float u, float v,
                                     int uw, int vh, int tw, int th, Operation<Void> original,
                                     @Local(argsOnly = true) GuiGraphics g, @Local(argsOnly = true) long elapsedMs) {
        if (dungeontrain$isNightCrest()) NightCrestPlaque.drawBackground(g, elapsedMs);
        else original.call(pose, x, y, w, h, u, v, uw, vh, tw, th);
    }

    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
    private void dungeontrain$challengeFlash(PoseStack pose, int x, int y, int w, int h, float u, float v,
                                             int uw, int vh, int tw, int th, Operation<Void> original,
                                             @Local(argsOnly = true) GuiGraphics g) {
        if (dungeontrain$isNightCrest()) NightCrestPlaque.drawFlash(g);
        else original.call(pose, x, y, w, h, u, v, uw, vh, tw, th);
    }

    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = BLIT, ordinal = 2))
    private void dungeontrain$otherFlash(PoseStack pose, int x, int y, int w, int h, float u, float v,
                                         int uw, int vh, int tw, int th, Operation<Void> original,
                                         @Local(argsOnly = true) GuiGraphics g) {
        if (dungeontrain$isNightCrest()) NightCrestPlaque.drawFlash(g);
        else original.call(pose, x, y, w, h, u, v, uw, vh, tw, th);
    }

    /** "Tab Complete!" in gold for "Challenge Complete!" — both the width and the draw read it. */
    @WrapOperation(method = DRAW, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/advancements/AdvancementType;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component dungeontrain$heading(AdvancementType type, Operation<Component> original) {
        return dungeontrain$isNightCrest() ? NightCrestPlaque.heading(dungeontrain$holder()) : original.call(type);
    }

    @WrapOperation(method = DRAW, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"))
    private void dungeontrain$chime(SoundManager sounds, SoundInstance sound, Operation<Void> original) {
        original.call(sounds, sound);
        if (dungeontrain$isNightCrest()) NightCrestPlaque.playChime();
    }
}
