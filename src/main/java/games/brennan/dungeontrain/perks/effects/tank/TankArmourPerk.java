package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Tank tier 2 "Armour": a flat {@link Attributes#ARMOR} bonus of {@link TankPerks#ARMOUR_PER_RANK} per rank,
 * kept as one permanent modifier on the player and refreshed once a second so rank changes (debris eaten,
 * death resetting to 0) take effect without any other hook. The bonus is player armour, not item armour, so
 * it sits outside any armour-tier comparison and flows through {@code combat.ArmorReductionCurve} like the
 * rest.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TankArmourPerk {

    static final ResourceLocation MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk/tank_armour");
    private static final int REFRESH_PERIOD_TICKS = 20;

    private TankArmourPerk() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % REFRESH_PERIOD_TICKS != 0) return;
        apply(player, Perks.rank(player, TankPerks.ARMOUR));
    }

    /** Set, replace or remove the modifier so it matches {@code rank}. Safe to call every second. */
    static void apply(ServerPlayer player, int rank) {
        AttributeInstance armour = player.getAttribute(Attributes.ARMOR);
        if (armour == null) return;
        double bonus = TankPerks.armourBonus(rank);
        AttributeModifier existing = armour.getModifier(MODIFIER_ID);
        if (bonus <= 0.0) {
            if (existing != null) armour.removeModifier(MODIFIER_ID);
            return;
        }
        if (existing != null && existing.amount() == bonus) return;
        armour.addOrReplacePermanentModifier(
            new AttributeModifier(MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
    }
}
