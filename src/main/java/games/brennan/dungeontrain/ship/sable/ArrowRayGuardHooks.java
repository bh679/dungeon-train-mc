package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Sable side of {@link ArrowRayGuard}, called from {@code AbstractArrowRayGuardMixin}.
 *
 * <p>{@link #guardTick} zeroes an arrow's movement for a tick whose block raycast would be
 * absurdly long. {@link #rehomeBeforeHitBlock} moves an arrow that is already in plot space back
 * to world space before Sable's {@code onHitBlock} redirect runs, because that redirect assumes
 * world space and otherwise sets a delta of about 20 million blocks. Each call logs one
 * {@code [arrow-ray-guard]} line, rate-limited, so player logs show when it fires.</p>
 */
public final class ArrowRayGuardHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger(DtLogging.JITTER + ".arrowray");

    /** At most this many guard lines per {@link #LOG_WINDOW_MS}. */
    private static final int LOG_BUDGET = 10;
    private static final long LOG_WINDOW_MS = 60_000L;

    /**
     * Dev/test switch {@code -Ddungeontrain.arrowRayGuard.logOnly=true}: log what the guard would do
     * but change nothing, to reproduce the freeze before and after in one build.
     */
    private static final boolean LOG_ONLY = Boolean.getBoolean("dungeontrain.arrowRayGuard.logOnly");

    private static long windowStartMs;
    private static int windowCount;

    private ArrowRayGuardHooks() {}

    /** @return true when the guard changed the arrow (its delta was zeroed). */
    public static boolean guardTick(AbstractArrow arrow, boolean inGround, boolean alreadyLogged) {
        if (arrow.isNoPhysics()) return false;
        Level level = arrow.level();
        Vec3 from = arrow.position();
        Vec3 delta = arrow.getDeltaMovement();
        ArrowRayGuard.Verdict verdict = ArrowRayGuard.verdict(from, delta, pos -> toWorld(level, pos));
        if (verdict == ArrowRayGuard.Verdict.OK) return false;
        if (!alreadyLogged) {
            log(verdict.name(), arrow, inGround, from, delta);
        }
        if (!LOG_ONLY) {
            arrow.setDeltaMovement(Vec3.ZERO);
        }
        return true;
    }

    /** @return true when the arrow was moved from plot space to world space. */
    public static boolean rehomeBeforeHitBlock(AbstractArrow arrow, boolean inGround, boolean alreadyLogged) {
        Level level = arrow.level();
        Vec3 pos = arrow.position();
        SubLevel sub = containing(level, pos);
        if (sub == null) return false;
        if (!alreadyLogged) {
            log("REHOME", arrow, inGround, pos, arrow.getDeltaMovement());
        }
        if (!LOG_ONLY) {
            Vec3 world = sub.logicalPose().transformPosition(pos);
            arrow.setPosRaw(world.x, world.y, world.z);
        }
        return true;
    }

    private static Vec3 toWorld(Level level, Vec3 pos) {
        SubLevel sub = containing(level, pos);
        return sub == null ? pos : sub.logicalPose().transformPosition(pos);
    }

    @Nullable
    private static SubLevel containing(Level level, Vec3 pos) {
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.z)) return null;
        return Sable.HELPER.getContaining(level, pos);
    }

    private static void log(String what, AbstractArrow arrow, boolean inGround, Vec3 from, Vec3 delta) {
        if (!takeLogSlot(System.currentTimeMillis())) return;
        Level level = arrow.level();
        Vec3 to = from.add(delta);
        SubLevel fromSub = containing(level, from);
        SubLevel toSub = containing(level, to);
        Entity owner = arrow.getOwner();
        LOGGER.info("[arrow-ray-guard] {}{} side={} type={} id={} owner={} inGround={} pos={} delta={} projFrom={} projTo={} fromSub={} toSub={}",
                what, LOG_ONLY ? "(log-only)" : "", level.isClientSide ? "client" : "server",
                arrow.getType().builtInRegistryHolder().key().location(), arrow.getId(),
                owner == null ? "none" : owner.getType().builtInRegistryHolder().key().location(), inGround,
                fmt(from), fmt(delta), fmt(toWorld(level, from)), fmt(toWorld(level, to)),
                fromSub == null ? "none" : fromSub.getUniqueId(),
                toSub == null ? "none" : toSub.getUniqueId());
    }

    private static synchronized boolean takeLogSlot(long nowMs) {
        if (nowMs - windowStartMs > LOG_WINDOW_MS) {
            windowStartMs = nowMs;
            windowCount = 0;
        }
        if (windowCount >= LOG_BUDGET) return false;
        windowCount++;
        return true;
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
