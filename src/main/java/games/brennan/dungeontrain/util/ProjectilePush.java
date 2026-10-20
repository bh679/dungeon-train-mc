package games.brennan.dungeontrain.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * The two extra steps a projectile needs when something other than its own flight changes its velocity each
 * tick, so its drift reads smoothly on clients. Shared by the train wind ({@code wind.WindPush}) and sphere
 * gravity ({@code worldgen.SphereGravityPush}):
 * <ol>
 *   <li>one {@linkplain #stuck stuck} in a block (it didn't move last tick) is left alone, or it would bank
 *       velocity it can't spend and be launched when it next comes free;</li>
 *   <li>a pushed one is {@linkplain #markPushed flagged} so the server sends its new velocity to clients this
 *       tick. Arrows otherwise sync motion only once a second; the client flew them without the push meanwhile
 *       and snapped them across when the sync came.</li>
 * </ol>
 */
public final class ProjectilePush {

    private ProjectilePush() {}

    /** True for a projectile that didn't move last tick: an arrow in a block, a projectile resting on the deck. */
    public static boolean stuck(Entity e) {
        return e instanceof Projectile && e.getX() == e.xo && e.getY() == e.yo && e.getZ() == e.zo;
    }

    /** After changing a projectile's velocity: have the server send it to clients this tick. No-op otherwise. */
    public static void markPushed(Entity e) {
        if (e instanceof Projectile) e.hasImpulse = true;
    }
}
