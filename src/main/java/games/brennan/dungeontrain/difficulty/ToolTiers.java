package games.brennan.dungeontrain.difficulty;

import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Digging tools on deep-run mobs: how likely a mob is to carry one, and what it may break with it.
 * Pure difficulty-level math, no world access; {@link DifficultyApplier} hands out the tool and
 * {@code combat.MobDigGoal} enforces {@link #mayBreak}.
 *
 * <p>Each break tier has its own spawn curve, starting fresh at its unlock level
 * ({@link #TIER_LEVELS}): {@link #START_CHANCE} there, {@link #ANCHOR_CHANCE} {@link #ANCHOR_SPAN}
 * levels later, then on toward (never reaching) 100%:
 * {@code 1 - (1 - start) * e^(-k (level - unlock level))}. A spawning mob rolls the unlocked tiers
 * highest first ({@link #rollTier}); the first that passes is its tier. So a newly unlocked tier is
 * rare at first while the lower ones keep climbing.</p>
 *
 * <p>The rolled tier is fixed on the mob at spawn, so a mob's reach never changes mid-life.</p>
 */
public final class ToolTiers {

    /** First difficulty level at which a mob may spawn with a digging tool. */
    public static final int TOOL_START_LEVEL = 50;
    /** A tier's spawn chance at its unlock level. */
    static final double START_CHANCE = 0.02;
    /** A tier's spawn chance {@link #ANCHOR_SPAN} levels after it unlocks. */
    static final double ANCHOR_CHANCE = 0.20;
    static final int ANCHOR_SPAN = 100;

    private static final double DECAY = Math.log((1.0 - START_CHANCE) / (1.0 - ANCHOR_CHANCE)) / ANCHOR_SPAN;

    /** Levels at which break tiers 1-4 unlock. */
    static final int[] TIER_LEVELS = {TOOL_START_LEVEL, 100, 150, 200};

    /** No break tier: the mob has no tool, or the level is below {@link #TOOL_START_LEVEL}. */
    public static final int NO_TIER = 0;

    private ToolTiers() {}

    /** Spawn chance of break tier {@code tier} (1-4) at {@code level}; 0 before it unlocks, always below 1. */
    public static double tierChance(int tier, int level) {
        if (tier < 1 || tier > TIER_LEVELS.length) return 0.0;
        int unlock = TIER_LEVELS[tier - 1];
        if (level < unlock) return 0.0;
        return 1.0 - (1.0 - START_CHANCE) * Math.exp(-DECAY * (level - unlock));
    }

    /** Overall chance a mob spawning at {@code level} carries a digging tool (any tier). */
    public static double toolChance(int level) {
        double none = 1.0;
        for (int tier = 1; tier <= TIER_LEVELS.length; tier++) none *= 1.0 - tierChance(tier, level);
        return 1.0 - none;
    }

    /**
     * Roll a spawning mob's break tier: each unlocked tier highest first, one {@code roll} draw
     * (uniform 0-1) each, the first that passes wins. {@link #NO_TIER} = no tool.
     */
    public static int rollTier(int level, DoubleSupplier roll) {
        for (int tier = breakTier(level); tier >= 1; tier--) {
            if (roll.getAsDouble() < tierChance(tier, level)) return tier;
        }
        return NO_TIER;
    }

    /**
     * Highest break tier unlocked at {@code level}:
     * <ol>
     *   <li>player-placed blocks its tool is the right tool for</li>
     *   <li>any player-placed block</li>
     *   <li>any player-placed block, plus any other block its tool is the right tool for</li>
     *   <li>any block</li>
     * </ol>
     * Floors are never breakable at any tier. {@link #NO_TIER} below {@link #TOOL_START_LEVEL}.
     * Which tier a given mob gets is {@link #rollTier}.
     */
    public static int breakTier(int level) {
        int tier = NO_TIER;
        for (int i = 0; i < TIER_LEVELS.length; i++) {
            if (level >= TIER_LEVELS[i]) tier = i + 1;
        }
        return tier;
    }

    /**
     * Whether a mob of break tier {@code tier} may break a block. {@code playerPlaced}: a player
     * added it; {@code correctTool}: the held tool is the right tool for it; {@code floor}: it lies
     * below the mob's feet.
     */
    public static boolean mayBreak(int tier, boolean playerPlaced, boolean correctTool, boolean floor) {
        if (floor) return false;
        return switch (tier) {
            case 1 -> playerPlaced && correctTool;
            case 2 -> playerPlaced;
            case 3 -> playerPlaced || correctTool;
            case 4 -> true;
            default -> false;
        };
    }

    /** Tool pool a mob at {@code level} draws from. */
    static List<DifficultyTier.WeightedItem> toolPool(int level) {
        return ProceduralTiers.toolPool(level);
    }
}
