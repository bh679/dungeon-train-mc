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
public record PerkMenuSnapshot(Set<String> unlocked, Map<String, Integer> active, int unlockPoints, int activePoints) {

    public static final PerkMenuSnapshot EMPTY = new PerkMenuSnapshot(Set.of(), Map.of(), 0, 0);

    public enum StarState { LOCKED, REACHABLE, UNLOCKED, ACTIVE }

    public static PerkMenuSnapshot current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return EMPTY;
        PerkState s = PerkClientState.stateFor(mc.player);
        return new PerkMenuSnapshot(s.unlocked(), s.active(), PerkClientBalances.unlockPoints(), PerkClientBalances.activePoints());
    }

    public boolean isUnlocked(String id) {
        return PerkTree.ROOT.equals(id) || unlocked.contains(id);
    }

    public int points(String id) {
        return active.getOrDefault(id, 0);
    }

    public StarState stateOf(PerkTree tree, String id) {
        if (PerkTree.ROOT.equals(id)) return StarState.ACTIVE;
        if (unlocked.contains(id)) return points(id) > 0 ? StarState.ACTIVE : StarState.UNLOCKED;
        return tree.reachable(id, this::isUnlocked) ? StarState.REACHABLE : StarState.LOCKED;
    }
}
