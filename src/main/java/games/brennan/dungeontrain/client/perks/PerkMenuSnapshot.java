package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.PerkClientBalances;
import games.brennan.dungeontrain.client.PerkClientState;
import games.brennan.dungeontrain.perks.PerkState;
import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Set;

/**
 * What the menu knows about the local player this frame: the synced {@link PerkState} plus the two
 * balances. Immutable; the screen takes one per frame and diffs it against the last to animate.
 */
public record PerkMenuSnapshot(Map<String, Integer> unlocks, Map<String, Integer> active, int unlockPoints, int activePoints) {

    public static final PerkMenuSnapshot EMPTY = new PerkMenuSnapshot(Map.of(), Map.of(), 0, 0);

    public enum StarState { LOCKED, REACHABLE, UNLOCKED, ACTIVE }

    public static PerkMenuSnapshot current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return EMPTY;
        PerkState s = PerkClientState.stateFor(mc.player);
        return new PerkMenuSnapshot(s.unlocks(), s.active(), PerkClientBalances.unlockPoints(), PerkClientBalances.activePoints());
    }

    /** The same unlocks with every point gone: how the tree stands for the next life. */
    public PerkMenuSnapshot withoutActive() {
        return new PerkMenuSnapshot(unlocks, Map.of(), unlockPoints, 0);
    }

    /** Each perk's points cut to {@code left} (0..1) of what it holds, to the nearest; a perk at 0 drops out, so small rings empty first. */
    public PerkMenuSnapshot drained(double left) {
        double f = Math.max(0, Math.min(1, left));
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        active.forEach((id, n) -> {
            int kept = (int) Math.round(n * f);
            if (kept > 0) out.put(id, kept);
        });
        return new PerkMenuSnapshot(unlocks, Map.copyOf(out), unlockPoints, activePoints);
    }

    public Set<String> unlocked() {
        return unlocks.keySet();
    }

    /** Times this perk is unlocked: the most points it can hold. */
    public int unlockLevel(String id) {
        return unlocks.getOrDefault(id, 0);
    }

    /** Can {@code p} be unlocked once more now: affordable and under the spent-gated limit? */
    public boolean canRaise(PerkTree tree, PerkTree.Perk p) {
        int spent = tree.unlockCostOf(unlocks);
        return unlockPoints >= p.unlockCost()
            && games.brennan.dungeontrain.perks.PerkUnlocks.allowed(unlockLevel(p.id()) + 1, spent);
    }

    public boolean isUnlocked(String id) {
        return PerkTree.ROOT.equals(id) || unlocks.containsKey(id);
    }

    /** Ranks bought this life across every perk (the web's {@code ranksHeld}). */
    public int ranksBought() {
        int sum = 0;
        for (int r : active.values()) sum += Math.max(0, r);
        return sum;
    }

    /** Active points the next rank costs (rises with every rank bought this life). */
    public int nextRankCost() {
        return games.brennan.dungeontrain.perks.PerkRankCost.nextCost(ranksBought());
    }

    /** Advancement Points spent on unlocks across the tree. */
    public int unlockSpent(PerkTree tree) {
        return tree.unlockCostOf(unlocks);
    }

    /** Enough active points for the next rank. */
    public boolean canAffordRank() {
        return activePoints > 0 && activePoints >= nextRankCost();
    }

    public int points(String id) {
        return active.getOrDefault(id, 0);
    }

    public StarState stateOf(PerkTree tree, String id) {
        if (PerkTree.ROOT.equals(id)) return StarState.ACTIVE;
        if (unlocks.containsKey(id)) return points(id) > 0 ? StarState.ACTIVE : StarState.UNLOCKED;
        if (!games.brennan.dungeontrain.perks.PerkRegistry.isBuilt(id)) return StarState.LOCKED; // Coming soon
        return tree.reachableNow(id, this::isUnlocked) ? StarState.REACHABLE : StarState.LOCKED;
    }
}
