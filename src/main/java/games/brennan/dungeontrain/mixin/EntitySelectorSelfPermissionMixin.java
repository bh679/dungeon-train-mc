package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.SelfSelectorGrant;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resolve-time half of {@link SelfSelectorGrant}. A parsed {@code @s} is re-checked when it is
 * resolved ({@code EntitySelector.checkPermissions}, via NeoForge's
 * {@code CommonHooks.canUseEntitySelectors}), and would throw "selector not allowed" for a non-op
 * even after {@link EntityArgumentSelfSelectorMixin} let it parse. Skip the check when the selector
 * is {@code @s} — the only selector that sets {@code currentEntity} — and the source is a
 * capstone-holder. Every other selector keeps vanilla's answer.
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorSelfPermissionMixin {

    @Shadow @org.spongepowered.asm.mixin.Final private boolean usesSelector;
    @Shadow @org.spongepowered.asm.mixin.Final private boolean currentEntity;

    @Inject(method = "checkPermissions", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$allowSelfSelector(CommandSourceStack source, CallbackInfo ci) {
        if (usesSelector && currentEntity && SelfSelectorGrant.holds(source)) {
            ci.cancel();
        }
    }
}
