package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.compat.PlayerMobLifeBridge;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaving something of worth in a drifting carriage's storage is remembered, and the player's next
 * echo comes back friendlier for it — the kind twin of {@code DriftingCarriageSabotageEvents}.
 *
 * <p>Driven from {@code SharedCarriageAdvancementEvents#onContainerClose}, which already reads a
 * container's contents at open and close. The worth is {@code LootValue.stackScore} summed over the
 * contents, so gear, rarity and enchantments count; raw materials score low.</p>
 *
 * <p><b>Not farmable by cycling.</b> Each player keeps a net-deposit {@link Ledger} per container:
 * taking items out lowers it, and only a rise above its previous peak is credited. Putting the same
 * sword in, taking it out and putting it back earns once. The ledger lives in memory and clears on
 * logout — a relog to farm one chest again is slow enough not to matter.</p>
 *
 * <p><b>Deliberately silent</b>, like sabotage: no chat line, no advancement. A carriage the player
 * authored does not count — storing your own things in your own build is not a gift.</p>
 */
public final class DriftGenerosity {

    /** Echo kindness per point of newly deposited worth — a diamond sword (~7) is about +0.35. */
    static final double KINDNESS_PER_VALUE = 0.05;
    /** Ceiling on the kindness one close can earn — the same as defending a PlayerMob. */
    static final double MAX_KINDNESS_PER_CLOSE = 1.0;

    /** A player's running net deposit into one container, and the highest it has reached. */
    record Ledger(double net, double peak) {
        static final Ledger EMPTY = new Ledger(0.0, 0.0);

        /** A fresh ledger with {@code delta} (negative for a withdrawal) applied. */
        Ledger apply(double delta) {
            double next = net + delta;
            return new Ledger(next, Math.max(peak, next));
        }
    }

    private record ContainerKey(ResourceKey<Level> level, BlockPos pos) {}

    private static final Map<UUID, Map<ContainerKey, Ledger>> LEDGERS = new ConcurrentHashMap<>();

    private DriftGenerosity() {}

    /** Worth deposited beyond anything credited before — the rise in the ledger's peak. */
    static double creditable(Ledger before, Ledger after) {
        return Math.max(0.0, after.peak() - before.peak());
    }

    /** Echo kindness for {@code newWorth} of fresh deposit, capped per close. */
    static float kindnessFor(double newWorth) {
        return (float) Math.min(Math.max(0.0, newWorth) * KINDNESS_PER_VALUE, MAX_KINDNESS_PER_CLOSE);
    }

    /**
     * Credit whatever worth the player left in the container at {@code pos} between open
     * ({@code valueBefore}) and close ({@code valueAfter}).
     */
    static void onClose(ServerPlayer player, SharedCarriageRegistry.Instance inst,
                        ResourceKey<Level> level, BlockPos pos, double valueBefore, double valueAfter) {
        if (inst.isAuthoredBy(player.getUUID())) return;
        double delta = valueAfter - valueBefore;
        if (delta == 0.0) return;
        Map<ContainerKey, Ledger> mine = LEDGERS.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>());
        ContainerKey key = new ContainerKey(level, pos.immutable());
        Ledger before = mine.getOrDefault(key, Ledger.EMPTY);
        Ledger after = before.apply(delta);
        mine.put(key, after);
        PlayerMobLifeBridge.creditGenerosity(player, kindnessFor(creditable(before, after)));
    }

    /** Forget a player's ledgers — called on logout. */
    static void forget(UUID playerId) {
        LEDGERS.remove(playerId);
    }
}
