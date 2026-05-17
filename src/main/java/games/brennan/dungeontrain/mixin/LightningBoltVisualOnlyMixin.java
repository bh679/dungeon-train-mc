package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import java.util.List;
import net.minecraft.advancements.critereon.LightningStrikeTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla {@link LightningBolt#tick()} fires
 * {@code CriteriaTriggers.LIGHTNING_STRIKE} unconditionally — the
 * {@code !visualOnly} guard inside {@code tick()} only covers
 * {@code thunderHit} + {@code CHANNELED_LIGHTNING}, not the
 * {@code LIGHTNING_STRIKE} criterion fired when the bolt is discarded.
 *
 * <p>Without this mixin, the welcome strike in {@code StartingBookEvents}
 * grants the vanilla "Surge Protector" advancement
 * ({@code minecraft:adventure/lightning_rod_with_villager_no_fire})
 * whenever a train carriage with a villager is within ~15 blocks of the
 * strike point — which is essentially always on first login.</p>
 *
 * <p>We patch this to honour the {@code visualOnly} contract for the
 * advancement trigger too. Semantically correct: {@code setVisualOnly(true)}
 * exists to make lightning purely cosmetic, and silently awarding a vanilla
 * advancement from a cosmetic effect is a vanilla oversight regardless of
 * who spawned the bolt.</p>
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltVisualOnlyMixin {

    @Shadow private boolean visualOnly;

    @WrapWithCondition(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/advancements/critereon/LightningStrikeTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/LightningBolt;Ljava/util/List;)V"
        )
    )
    private boolean dungeontrain$skipAdvancementTriggerIfVisualOnly(
        LightningStrikeTrigger trigger, ServerPlayer player, LightningBolt bolt, List<Entity> entities
    ) {
        return !this.visualOnly;
    }
}
