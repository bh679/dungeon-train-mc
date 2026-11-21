package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PerkBalancesPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pushes the two perk balances to the owning client ({@link PerkBalancesPacket}) and installs the
 * "unlock points spent" rule the earning side's {@link PerkSpend} seam asks for: the sum of the tier
 * costs of every unlocked perk the bundled tree knows.
 */
public final class PerkBalances {

    private PerkBalances() {}

    /** Call once at mod construction. */
    public static void install() {
        PerkSpend.setSpentFunction(player -> PerkTreeLoader.get().unlockCostOf(Perks.state(player).unlocks()));
    }

    public static int unlockAvailable(ServerPlayer player) {
        return PerkPoints.unlockAvailable(player);
    }

    public static void sync(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new PerkBalancesPacket(PerkPoints.unlockAvailable(player), PerkPoints.active(player)));
    }
}
