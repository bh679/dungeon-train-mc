package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.AdvancementTabGate;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the earned card for advancements outside Dungeon Train's tabs — see
 * {@link AdvancementTabGate#shouldSuppressCard}. Gating the {@code addToast} call itself means no
 * {@code AdvancementToast} is ever built, so Advancement Plaques (which re-skins those toasts) shows
 * nothing either. The server's chat announcement is separate and unaffected. Any error keeps the card.
 */
@Mixin(ClientAdvancements.class)
public abstract class ClientAdvancementsCardGateMixin {

    @WrapWithCondition(
        method = "update",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"))
    private boolean dungeontrain$cardOnlyDungeonTrainTabs(ToastComponent toasts, Toast toast,
                                                         @Local AdvancementNode node) {
        try {
            return !AdvancementTabGate.shouldSuppressCard(node);
        } catch (Throwable t) {
            return true;
        }
    }
}
