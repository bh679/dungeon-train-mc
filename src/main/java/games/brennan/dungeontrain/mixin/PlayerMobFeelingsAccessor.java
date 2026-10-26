package games.brennan.dungeontrain.mixin;

import games.brennan.playermob.entity.FeelingLedger;
import games.brennan.playermob.entity.PlayerMobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads a PlayerMob's private feeling ledger so perks can nudge how much it likes a player. */
@Mixin(PlayerMobEntity.class)
public interface PlayerMobFeelingsAccessor {

    @Accessor("feelings")
    FeelingLedger dungeontrain$feelings();
}
