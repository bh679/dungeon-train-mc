package games.brennan.dungeontrain.compat;

/**
 * "A PlayerMob is dropping an item right now." Entered and left around {@code Entity.spawnAtLocation}
 * on a PlayerMob by {@code mixin.EchoDropCreditMixin}, so the {@code EntityJoinLevelEvent} that fires
 * inside it can tell a PlayerMob's drop (death loot, backpack spill, hand-over) from a player's: a
 * disposable-camera photograph ignites on a player's drop, never on a PlayerMob's. Server thread only,
 * synchronous, nestable.
 */
public final class PlayerMobDrops {

    private static int depth;

    private PlayerMobDrops() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) depth--;
    }

    /** True while a PlayerMob's {@code spawnAtLocation} is on the stack. */
    public static boolean inProgress() {
        return depth > 0;
    }
}
