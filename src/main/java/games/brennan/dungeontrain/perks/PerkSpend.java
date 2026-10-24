package games.brennan.dungeontrain.perks;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.ToIntFunction;

/**
 * Seam for the unlock points a player has spent. The perk foundation ({@code PerkState}) owns which
 * perks are unlocked; it installs {@link #setSpentFunction} with "sum of the tier costs (1/5/10) of
 * the unlocked perks". Until it does, nothing is spent.
 */
public final class PerkSpend {

    private static volatile ToIntFunction<ServerPlayer> spent = player -> 0;

    private PerkSpend() {}

    public static void setSpentFunction(ToIntFunction<ServerPlayer> fn) {
        spent = fn;
    }

    public static int unlockSpent(ServerPlayer player) {
        return spent.applyAsInt(player);
    }
}
