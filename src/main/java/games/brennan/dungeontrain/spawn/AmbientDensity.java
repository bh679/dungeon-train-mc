package games.brennan.dungeontrain.spawn;

import games.brennan.dungeontrain.portal.PortalTwinSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How many ambient monsters are already standing around each player — the number
 * {@code AmbientDensityGuard} caps.
 *
 * <h2>Why a Dungeon Train world needs this and a vanilla one does not</h2>
 * <p>Vanilla's monster cap is a <b>level-wide</b> budget of about 70, and in an ordinary world most
 * of it is spent out of sight: the caves that soak it up run a hundred blocks below a player on the
 * surface, well outside the 128-block sphere anything spawns in. A Dungeon Train overworld generates
 * from y=32 inside a dimension that starts at −64, so that entire underground is compressed into the
 * shell directly beneath the track. The same budget, spent in the player's lap.</p>
 *
 * <p>Measured at one pillager outpost, same seed in both world types: 59 monsters within 96 blocks of
 * the player in the DT world against 19 in the vanilla one, while the level-wide totals matched at
 * ~63. The outpost then converts that pressure into pillagers in particular, because its
 * {@code spawn_overrides} cover the structure's whole bounding box and pillagers spawn on block light
 * rather than sky light — so they keep coming at noon, when nothing else can.</p>
 *
 * <p>The cap is therefore <b>local</b>, not a smaller global budget: it does nothing where density is
 * already reasonable and only trims the places where the world has stacked its whole allowance on top
 * of one player.</p>
 *
 * <h2>What counts</h2>
 * <p>Two exclusions, and both matter more than they look.</p>
 * <ul>
 *   <li><b>Persistent mobs are not counted</b>, which is exactly the rule
 *       {@code NaturalSpawner.createState} applies to vanilla's own cap. Everything Dungeon Train
 *       spawns — carriage contents, PlayerMobs, Death Note echoes, a room's authored mobs — is
 *       stamped {@code PersistenceRequired}, so a train packed with mobs never suppresses the
 *       ambient world around it. The authored difficulty curve and this cap do not see each other.</li>
 *   <li><b>Twin-space mobs are not counted.</b> A portal room or chunk dimension sits in the sealed
 *       basement under the train, within the radius but in another world as far as a player on the
 *       deck is concerned. Its occupants are kept alive deliberately by {@code PortalDespawnEvents}
 *       and are not persistent, so without this they would quietly spend the surface's budget.</li>
 * </ul>
 *
 * <h2>Sampled, not counted per attempt</h2>
 * <p>{@code FinalizeSpawnEvent} fires many times a tick, and an entity query per attempt would put an
 * AABB sweep on one of the hottest paths in the game. The counts are refreshed once every
 * {@link #REFRESH_TICKS} instead and read from the cache, which can lag a burst by up to a second —
 * worth a handful of extra spawns before the cap re-reads.</p>
 */
public final class AmbientDensity {

    /** How far around a player counts as "around" — the radius the density was measured at. */
    public static final int RADIUS = 96;

    /** How often the per-player counts are recomputed. */
    public static final int REFRESH_TICKS = 20;

    /** Player id → ambient monsters within {@link #RADIUS} at the last refresh. */
    private static final Map<UUID, Integer> COUNTS = new HashMap<>();

    private AmbientDensity() {}

    /**
     * Whether a count has reached the cap.
     *
     * <p>A cap of zero is off, not "no monsters at all" — an off switch is the more useful reading of
     * the bottom of the range, and "cancel every ambient spawn in the world" is already available by
     * turning mob spawning off.</p>
     */
    public static boolean atCap(int nearbyCount, int cap) {
        return cap > 0 && nearbyCount >= cap;
    }

    /** Whether {@code mob} counts against a player's ambient allowance. */
    public static boolean counts(ServerLevel level, Mob mob) {
        if (mob.getType().getCategory() != MobCategory.MONSTER) return false;
        // The same exemption vanilla's own cap applies — and in a DT world it is what keeps the
        // train's own population out of the sum.
        if (mob.isPersistenceRequired() || mob.requiresCustomPersistence()) return false;
        return !PortalTwinSpace.isInside(level, mob.getBlockX(), mob.getY());
    }

    /** Recompute every player's count. Called on the refresh tick, never per spawn attempt. */
    public static void refresh(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            AABB around = player.getBoundingBox().inflate(RADIUS);
            COUNTS.put(player.getUUID(),
                    level.getEntitiesOfClass(Mob.class, around, m -> counts(level, m)).size());
        }
    }

    /**
     * The ambient count for the player nearest {@code (x, y, z)}, or 0 where there is nobody near
     * enough for the position to be inside anyone's allowance.
     *
     * <p>Nearest rather than "any player over the cap": in multiplayer a crowded camp on one side of
     * the map should not stop the world spawning around somebody standing alone on the other.</p>
     */
    public static int nearestCount(ServerLevel level, double x, double y, double z) {
        Player nearest = level.getNearestPlayer(x, y, z, RADIUS, false);
        if (nearest == null) return 0;
        return COUNTS.getOrDefault(nearest.getUUID(), 0);
    }

    /** Drop everything remembered — server stopping, or a player leaving. */
    public static void forget(UUID playerId) {
        COUNTS.remove(playerId);
    }

    /** Drop every remembered count. */
    public static void clear() {
        COUNTS.clear();
    }
}
