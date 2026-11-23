package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.DeadPlayerAdvancementGate;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuse advancements while the player lies dead ({@link DeadPlayerAdvancementGate}). Returning
 * {@code false} is what {@code award} does for an already-granted criterion, so callers need no special case.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsDeadGateMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$refuseWhileDead(AdvancementHolder holder, String criterion,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (player == null || !DeadPlayerAdvancementGate.refuses(player, holder.id())) return;
        dungeontrain$LOGGER.debug("[DungeonTrain] Refused {} ({}) for {}: dead", holder.id(), criterion,
            player.getGameProfile().getName());
        cir.setReturnValue(false);
    }
}
