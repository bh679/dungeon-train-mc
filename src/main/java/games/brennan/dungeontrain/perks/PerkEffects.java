package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registration point for perk effect handlers. {@link Perks} and {@link PerkLifecycle} fan
 * out to every registered {@link PerkEffect}; one failing handler is logged and does not stop
 * the others.
 */
public final class PerkEffects {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CopyOnWriteArrayList<PerkEffect> EFFECTS = new CopyOnWriteArrayList<>();

    private PerkEffects() {}

    public static void register(PerkEffect effect) {
        if (effect == null) throw new IllegalArgumentException("perk effect is null");
        EFFECTS.addIfAbsent(effect);
    }

    /** Registered handlers, registration order. */
    public static List<PerkEffect> all() {
        return List.copyOf(EFFECTS);
    }

    static void fireRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        if (oldRank == newRank) return;
        BuiltPerks.bootstrap(); // the classes' declare() register their handlers
        for (PerkEffect effect : EFFECTS) {
            try {
                effect.onRankChanged(player, perkId, oldRank, newRank);
            } catch (RuntimeException e) {
                LOGGER.error("[DungeonTrain] perk effect {} failed on rank change {} {}->{} for {}",
                    effect.getClass().getSimpleName(), perkId, oldRank, newRank, player.getScoreboardName(), e);
            }
        }
    }

    static void firePlayerReady(ServerPlayer player) {
        BuiltPerks.bootstrap();
        for (PerkEffect effect : EFFECTS) {
            try {
                effect.onPlayerReady(player);
            } catch (RuntimeException e) {
                LOGGER.error("[DungeonTrain] perk effect {} failed on player ready for {}",
                    effect.getClass().getSimpleName(), player.getScoreboardName(), e);
            }
        }
    }
}
