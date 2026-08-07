package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What a severing looks and sounds like: a sheet of glass going with a beacon dying under it, and
 * then something soul-ish guttering at the hole for a few seconds afterwards.
 *
 * <p>Played in <b>both</b> copies. The two corridors are in completely different parts of the world
 * — one riding the train, one at the world floor — and the player is in exactly one of them at the
 * moment it happens, with no way of knowing which from here.</p>
 *
 * <p><b>The carriage-side emitter follows the train.</b> Its site is stored as a corridor-local cell
 * and re-resolved against the live pairing every tick, not baked into a world position. A carriage
 * travels several blocks a second, so a fixed position would leave the particles hanging in the air
 * behind it while the hole they belong to rode away. The twin does not move and is stored plainly.</p>
 */
public final class PortalSeverEffects {

    /**
     * The lingering particle. <b>This is the dial</b> — swap it for any other soul-flavoured type
     * and nothing else needs to change.
     */
    private static final ParticleOptions LINGER = ParticleTypes.SOUL;

    /** A sparser second type on top, for a bit of light in a dark corridor. */
    private static final ParticleOptions ACCENT = ParticleTypes.SOUL_FIRE_FLAME;

    /** The shatter. Pitched down from a pane of glass to something heavier. */
    private static final float GLASS_VOLUME = 1.4f;
    private static final float GLASS_PITCH = 0.65f;

    /**
     * A beacon switching off, laid under the shatter.
     *
     * <p>The glass alone says <i>damaged</i>. The falling pair of tones underneath it is what makes
     * the moment read as <i>lost</i> — which is the accurate description, since the corridor is not
     * broken so much as permanently one-way from here on. Quieter than the glass so it reads as the
     * tail of the break rather than a second event.</p>
     */
    private static final float BEACON_VOLUME = 0.8f;
    private static final float BEACON_PITCH = 1.0f;

    /**
     * How long the hole keeps smoking.
     *
     * <p>70 ticks rather than a round 60 because {@code block.beacon.deactivate} is a 3.5-second
     * clip: at 60 the tone outlives the particles and the effect ends twice. Matching the two means
     * the corridor goes quiet and stops smoking together.</p>
     */
    private static final int DURATION_TICKS = 70;

    /** Particles per emitting tick, per type, per copy. */
    private static final int LINGER_COUNT = 6;
    private static final int ACCENT_COUNT = 2;

    /** Every other tick — a continuous stream at this count reads as a fog bank rather than a wisp. */
    private static final int EMIT_EVERY = 2;

    /** How far particles wander from the hole, in blocks. */
    private static final double SPREAD = 0.35;

    /** Drift speed. Soul particles rise on their own; this only nudges them. */
    private static final double DRIFT = 0.015;

    /**
     * A hole that is still smoking.
     *
     * <p>{@code carriageIndex} of {@link #STATIC_SITE} means the position is already final — the
     * twin's, which does not move. Anything else is a corridor-local cell to be re-resolved against
     * that carriage's live pairing each tick.</p>
     */
    private record Site(int carriageIndex, int localX, int localY, int localZ,
                        BlockPos fixed, long endsAt) {}

    private static final int STATIC_SITE = Integer.MIN_VALUE;

    /**
     * Concurrent because {@link #begin} is reached from Sable's block-change hook while {@link #tick}
     * runs on the level tick, and the two are not guaranteed to be the same thread.
     */
    private static final Queue<Site> SITES = new ConcurrentLinkedQueue<>();

    private PortalSeverEffects() {}

    /**
     * Sound the break in both copies and start both emitters.
     *
     * @param local the corridor-local cell of the hole, which is what the carriage-side emitter
     *              tracks — the cell stays put as the train travels, the world position does not
     */
    public static void begin(ServerLevel level, PortalPairIndex.Entry entry, int[] local,
                             BlockPos carriagePos, BlockPos twinPos) {
        long endsAt = level.getGameTime() + DURATION_TICKS;

        // Pitched down from a pane of glass to something heavier — this is a connection breaking,
        // not a window. Played to everyone in earshot rather than at a player, since the other half
        // of the pair may hold someone who did not do it.
        playBreak(level, carriagePos);
        playBreak(level, twinPos);

        SITES.add(new Site(STATIC_SITE, 0, 0, 0, twinPos, endsAt));
        SITES.add(new Site(entry.carriageIndex(), local[0], local[1], local[2], carriagePos, endsAt));
    }

    /**
     * The sever, in two layers played on the same tick: the shatter, and the beacon falling away
     * under it.
     *
     * <p>Passed a {@code null} player so it goes to everyone in earshot rather than to whoever swung
     * the pickaxe — the other copy may hold someone who did not.</p>
     */
    private static void playBreak(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS,
            GLASS_VOLUME, GLASS_PITCH);
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS,
            BEACON_VOLUME, BEACON_PITCH);
    }

    /** Advance every live emitter, dropping the ones that have burned out. Cheap when idle. */
    public static void tick(ServerLevel level) {
        if (SITES.isEmpty()) return;

        long now = level.getGameTime();
        SITES.removeIf(site -> now >= site.endsAt());
        if (now % EMIT_EVERY != 0) return;

        for (Site site : SITES) {
            BlockPos pos = resolve(site);
            if (pos == null) continue;

            double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
            level.sendParticles(LINGER, x, y, z, LINGER_COUNT, SPREAD, SPREAD, SPREAD, DRIFT);
            level.sendParticles(ACCENT, x, y, z, ACCENT_COUNT, SPREAD, SPREAD, SPREAD, DRIFT);
        }
    }

    /**
     * Where a site is right now, or {@code null} if its corridor has gone out of range — in which
     * case nobody is near enough to see the particles anyway.
     */
    private static BlockPos resolve(Site site) {
        if (site.carriageIndex() == STATIC_SITE) return site.fixed();

        PortalPairIndex.Entry entry = PortalPairIndex.byCarriage(site.carriageIndex());
        if (entry == null) return null;

        return BlockPos.containing(
            entry.carriageWorld().x + site.localX() + 0.5,
            entry.carriageWorld().y + site.localY() + 0.5,
            entry.carriageWorld().z + site.localZ() + 0.5);
    }

    /** Drop every emitter, for the same reason {@code PortalCarriageEvents} clears its maps. */
    public static void clear() {
        SITES.clear();
    }
}
