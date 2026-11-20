package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The blasts of the last couple of ticks, with their true centre and radius. A death by explosion
 * only carries the source entity's position ({@code DamageSource#getSourcePosition}), which for a
 * creeper or TNT is not where the blast was, and no radius at all, so the death body's throw
 * ({@code ship.sable.DeathKnockback}) looks the blast up here. {@code ExplosionEvent.Detonate}
 * fires before the blast hurts anyone, so the entry is in place when the death follows in the same
 * tick. Server thread only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RecentExplosions {

    /** A blast is kept this many game ticks after it went off. */
    static final long KEEP_TICKS = 2;
    /** Never hold more than this many (a chain of TNT); the oldest go first. */
    static final int MAX_ENTRIES = 64;

    /** One blast: where, how big, in which dimension, at what game time. */
    public record Blast(ResourceKey<Level> dimension, Vec3 centre, float radius, long gameTime) {}

    private static final List<Blast> BLASTS = new ArrayList<>();

    private RecentExplosions() {}

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        record(new Blast(level.dimension(), event.getExplosion().center(), event.getExplosion().radius(), level.getGameTime()));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BLASTS.clear();
    }

    static void record(Blast blast) {
        BLASTS.removeIf(b -> blast.gameTime() - b.gameTime() > KEEP_TICKS);
        if (BLASTS.size() >= MAX_ENTRIES) BLASTS.remove(0);
        BLASTS.add(blast);
    }

    /**
     * The recent blast in {@code dimension} that reaches {@code body} and is nearest it, or null.
     * {@code toWorld} maps a centre into world space (a blast inside a carriage plot reports
     * shipyard coordinates); pass the identity off a carriage.
     */
    @Nullable
    public static Blast nearest(ResourceKey<Level> dimension, Vec3 body, long gameTime,
                                UnaryOperator<Vec3> toWorld) {
        return nearest(BLASTS, dimension, body, gameTime, toWorld);
    }

    /** Pure: the pick behind {@link #nearest(ResourceKey, Vec3, long, UnaryOperator)}; centres come back in world space. */
    @Nullable
    static Blast nearest(List<Blast> blasts, ResourceKey<Level> dimension, Vec3 body, long gameTime,
                         UnaryOperator<Vec3> toWorld) {
        Blast best = null;
        double bestDist = Double.MAX_VALUE;
        for (Blast b : blasts) {
            if (!b.dimension().equals(dimension) || gameTime - b.gameTime() > KEEP_TICKS) continue;
            for (Vec3 centre : List.of(b.centre(), toWorld.apply(b.centre()))) {
                double d = centre.distanceTo(body);
                if (d >= 2.0 * b.radius() || d >= bestDist) continue;
                bestDist = d;
                best = new Blast(b.dimension(), centre, b.radius(), b.gameTime());
            }
        }
        return best;
    }

    /** Test hook: forget every blast. */
    static void clear() {
        BLASTS.clear();
    }
}
