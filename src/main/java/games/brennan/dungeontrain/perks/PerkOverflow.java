package games.brennan.dungeontrain.perks;

import net.minecraft.util.RandomSource;

/**
 * The "overflow" curve several perks share: a percentage that grows with points, where every full
 * 100% is a guaranteed step and the remainder is the chance of one step more. There is no cap: past
 * 100% a perk keeps climbing towards its next step (Crits, Vampirism, Luck, Looting).
 */
public final class PerkOverflow {

    private PerkOverflow() {}

    /** Steps every roll gets at {@code percent}: one per full 100%. */
    public static int guaranteed(int percent) {
        return percent <= 0 ? 0 : percent / 100;
    }

    /** The chance, in percent, of one step past {@link #guaranteed}. */
    public static int remainder(int percent) {
        return percent <= 0 ? 0 : percent % 100;
    }

    /** One roll: {@link #guaranteed} steps plus a {@link #remainder}% chance of one more. */
    public static int rollSteps(int percent, RandomSource random) {
        if (percent <= 0) return 0;
        int steps = guaranteed(percent);
        int rest = remainder(percent);
        if (rest > 0 && random.nextInt(100) < rest) steps++;
        return steps;
    }
}
