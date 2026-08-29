package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalPuppetsPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stand-ins for the entities in the <b>other</b> half of a portal pair.
 *
 * <p>The corridor illusion works for one player and breaks for two. The swap decides which copy a
 * player belongs in by which side of the midpoint they are on, so two players walking towards each
 * other end up in different copies — one on the train, one at the world floor, far outside entity
 * tracking range. They vanish from each other's view exactly where the corridor is supposed to feel
 * like one continuous room. A puppet is the counterpart the other player can see: the same entity,
 * at the mirrored position, in the copy they are standing in.</p>
 *
 * <p><b>A puppet is not an entity.</b> Nothing is spawned here — not on the server, not in the client
 * level. This class produces a per-tick description that {@code client/portal/PortalPuppetsClient}
 * draws as a render model and nothing else. Two of this feature's hard constraints fall out of that
 * rather than being defended:</p>
 *
 * <ul>
 *   <li><b>No re-entrancy.</b> A puppet cannot spawn a puppet, because the scan below only ever sees
 *       real entities — there is nothing of ours in the level to find. Contrast
 *       {@link PortalEditMirror}, whose mirrored write lands back in the same hook it came from and
 *       needs a thread-local guard to break the loop.</li>
 *   <li><b>No interaction.</b> There is nothing to collide with, tick, aggro, damage, loot or
 *       persist, and nothing for DT's own entity scanners (social tracking, ride photos, advancement
 *       triggers) to mistake for a real mob.</li>
 * </ul>
 *
 * <p><b>Cost.</b> Everything here runs behind the {@code occupied} test
 * {@code PortalCarriageEvents} already computes per pair, so an empty corridor — which is nearly all
 * of them, nearly all the time — adds one boolean to the tick and nothing else.</p>
 */
public final class PortalPuppets {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Most puppets one pair will describe in a tick.
     *
     * <p>A corridor holding two players is the case this exists for; a corridor holding forty
     * zombies is a spawner someone built in it. The cap keeps a snapshot bounded either way, and
     * what it drops is logged rather than silently trimmed.</p>
     */
    public static final int MAX_PER_PAIR = 16;

    /**
     * How far outside a corridor a player still receives its puppets.
     *
     * <p>Corridor containment alone is a beat too late: a player standing in the doorway of the
     * neighbouring carriage can see down the corridor, and a puppet that only appeared once they
     * stepped over the threshold would pop into existence in front of them. The corridor is nine
     * blocks long, so this reaches a little past either door and no further.</p>
     */
    private static final double VIEW_RANGE = 16.0;

    /** Carriage index → the puppets it described last tick, keyed by source entity id, for logging. */
    private static final Map<Integer, Map<Integer, String>> LIVE = new HashMap<>();

    /** Players who were sent a non-empty snapshot last tick and therefore need a clearing one. */
    private static final Set<UUID> SENT = new HashSet<>();

    private PortalPuppets() {}

    /**
     * One tick's worth of puppets, accumulated across every portal pair before anything is sent.
     *
     * <p>Per tick and per <i>player</i>, not per pair: a player near two pairs would otherwise get
     * two packets that each look like the complete picture, and the second would clear the first.</p>
     */
    public static final class Session {

        private final Map<UUID, List<PortalPuppetsPacket.Entry>> byViewer = new HashMap<>();

        private Session() {}

        private void add(ServerPlayer viewer, PortalPuppetsPacket.Entry entry) {
            byViewer.computeIfAbsent(viewer.getUUID(), k -> new ArrayList<>()).add(entry);
        }

        /**
         * Send each player their puppets, and each player who had some and no longer does an empty
         * snapshot.
         *
         * <p>The clearing snapshot is what makes a corridor emptying an <i>event</i> the client can
         * act on. Its own staleness timeout would get there eventually, but not before a puppet had
         * stood frozen in the room for a couple of seconds after the player it stands for walked
         * out.</p>
         */
        public void dispatch(List<ServerPlayer> players) {
            for (ServerPlayer player : players) {
                List<PortalPuppetsPacket.Entry> entries = byViewer.get(player.getUUID());

                if (entries != null && !entries.isEmpty()) {
                    DungeonTrainNet.sendTo(player, new PortalPuppetsPacket(entries));
                    SENT.add(player.getUUID());
                } else if (SENT.remove(player.getUUID())) {
                    DungeonTrainNet.sendTo(player, PortalPuppetsPacket.empty());
                }
            }

            // A player who logged out or changed dimension mid-corridor is no longer in this list, so
            // the branch that would have forgotten them never runs and their id would sit here for
            // the rest of the session. Their client is covered either way — it drops puppets on its
            // own if the snapshots stop — but the set should not grow without bound.
            if (SENT.size() > players.size()) {
                Set<UUID> here = new HashSet<>();
                for (ServerPlayer player : players) here.add(player.getUUID());
                SENT.retainAll(here);
            }
        }
    }

    public static Session begin() {
        return new Session();
    }

    /**
     * Describe every entity in one pair's two corridors as a puppet in the opposite one, and hand
     * each to the players who should see it.
     *
     * @param frames        the live pair mapping, read fresh this tick
     * @param ship          the portal carriage, for converting a carriage-side puppet into the plot
     *                      space its blocks actually live in
     * @param carriageIndex the portal carriage's index, which keys this pair's logging
     */
    public static void gather(ServerLevel level, List<ServerPlayer> players, PortalFrames frames,
                              ManagedShip ship, int carriageIndex, List<Entity> occupants,
                              Session session) {
        List<PortalPuppetsPacket.Entry> entries = new ArrayList<>();
        Map<Integer, String> live = new HashMap<>();

        List<Entity> sources = new ArrayList<>(occupants);

        // Players first, so a corridor crowded past the cap keeps the puppets this feature exists
        // for and drops the scenery rather than the other way round.
        sources.sort(Comparator.comparingInt(e -> e instanceof ServerPlayer ? 0 : 1));

        int dropped = 0;
        for (Entity source : sources) {
            if (!eligible(source)) continue;

            if (entries.size() >= MAX_PER_PAIR) {
                dropped++;
                continue;
            }

            PortalPuppetsPacket.Entry entry = describe(frames, ship, source);
            if (entry == null) continue;

            entries.add(entry);
            live.put(entry.key(), label(frames, source));
        }

        if (dropped > 0) {
            LOGGER.info("[DungeonTrain] Portal puppets capped at {} for carriage {} — {} not described",
                MAX_PER_PAIR, carriageIndex, dropped);
        }

        logTransitions(carriageIndex, live);

        if (entries.isEmpty()) return;

        for (ServerPlayer viewer : players) {
            if (!canSee(frames, viewer)) continue;
            for (PortalPuppetsPacket.Entry entry : entries) {
                // Never your own stand-in. Filtered per recipient rather than hidden client-side, so
                // a player's puppet is not merely invisible to them — it never reaches them.
                if (entry.key() == viewer.getId()) continue;
                session.add(viewer, entry);
            }
        }
    }

    /** Drop a pair's puppets — it is out of range, or nobody is in it any more. */
    public static void forget(int carriageIndex) {
        logTransitions(carriageIndex, Map.of());
        LIVE.remove(carriageIndex);
    }

    /** Forget everything, for a world unload or a server stop. */
    public static void clear() {
        LIVE.clear();
        SENT.clear();
    }

    /**
     * Whether an entity gets a stand-in at all.
     *
     * <p>Passengers are skipped for the same reason the swap skips them — they are being carried, and
     * their position is their vehicle's business. A spectator has no body to mirror, and an entity
     * that has made itself invisible should not be given away by its puppet.</p>
     *
     * <p>Everything else qualifies, not only mobs: a dropped item, a thrown ender pearl and a
     * painting are all physically in the room, and the room is meant to look the same from either
     * copy. The appearance of whatever it turns out to be is carried by its synched data rather than
     * by anything type-specific here, so this needs no list of what is supported.</p>
     */
    private static boolean eligible(Entity entity) {
        if (!entity.isAlive() || entity.isPassenger() || entity.isSpectator()) return false;
        if (entity.isInvisible()) return false;
        return true;
    }

    /** One entity's puppet, or {@code null} if it turns out not to be in either corridor. */
    private static PortalPuppetsPacket.Entry describe(PortalFrames frames, ManagedShip ship,
                                                      Entity source) {
        PortalFrames.Move dest = frames.mirror(source.getX(), source.getY(), source.getZ());
        if (dest == null) return null;

        // A grounded entity goes to the destination's floor surface rather than its carried-across
        // local Y. The two frames' block grids differ by the ship's fractional pose, so the verbatim
        // offset stands a puppet a fraction inside the floor or a fraction above it — the same
        // mismatch that used to drop swapping players through a twin.
        //
        // An item is the exception, and visibly so. It bobs, so its onGround flickers, so the clamp
        // and the carried-across Y alternate as the answer from tick to tick — and the client lerps
        // between whichever two it was last sent, which reads as a shake. Its mirrored Y already
        // carries the difference between the two origins, which is the whole of what the clamp
        // corrects for, so it needs none.
        double y = source.onGround() && !(source instanceof ItemEntity)
            ? frames.floorSurfaceY(dest.toFrame())
            : dest.y();

        double x = dest.x();
        double z = dest.z();
        UUID subLevel = null;

        if (dest.toFrame() == PortalFrames.FRAME_CARRIAGE) {
            // Riding the train. World coordinates would be derived from the live ship AABB and would
            // therefore carry the group's jitter into a puppet standing on blocks that jitter with
            // it — visible shimmer against its own floor. Shipyard-local coordinates do not move at
            // all while the source stands still, and the client resolves them against the same pose
            // Sable draws the carriage blocks with. Converted through the ship's own transform, never
            // by assuming the plot's axes run the same way as the world's.
            Vector3d plot = ship.worldToShip(new Vector3d(x, y, z));
            x = plot.x;
            y = plot.y;
            z = plot.z;
            subLevel = ship.subLevelId();
        }

        boolean isPlayer = source instanceof ServerPlayer;
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(source.getType());
        LivingEntity living = source instanceof LivingEntity le ? le : null;

        // The whole of the source's non-default synched data, which is what makes the puppet the
        // same creature rather than a fresh one of the same species — a snow villager stays a snow
        // villager, a charged creeper stays charged. Read-only: getNonDefaultValues does not touch
        // the dirty flags, which belong to the real entity's own tracker. Null means "all default".
        List<SynchedEntityData.DataValue<?>> data = source.getEntityData().getNonDefaultValues();
        if (data == null) data = List.of();

        return new PortalPuppetsPacket.Entry(
            source.getId(),
            isPlayer ? PortalPuppetsPacket.KIND_PLAYER : PortalPuppetsPacket.KIND_MOB,
            typeId,
            isPlayer ? source.getUUID() : null,
            isPlayer ? ((ServerPlayer) source).getGameProfile().getName() : "",
            subLevel,
            x, y, z,
            // Body yaw, not the entity's own yRot: for a mob those differ while it turns, and the
            // body is what the renderer squares the model up with. Only living things have one.
            living != null ? living.yBodyRot : source.getYRot(),
            source.getYHeadRot(),
            source.getXRot(),
            data,
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.MAINHAND).copy(),
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.HEAD).copy(),
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.CHEST).copy(),
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.LEGS).copy(),
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.FEET).copy());
    }

    /** True if this player is close enough to either corridor to be shown its puppets. */
    private static boolean canSee(PortalFrames frames, ServerPlayer viewer) {
        return within(frames, PortalFrames.FRAME_CARRIAGE, viewer)
            || within(frames, PortalFrames.FRAME_TWIN, viewer);
    }

    private static boolean within(PortalFrames frames, int frame, ServerPlayer viewer) {
        PortalFrames.Origin o = frames.originOf(frame);
        PortalCarriageLayout layout = frames.layout();
        double cx = o.x() + layout.length() / 2.0;
        double cy = o.y() + layout.height() / 2.0;
        double cz = o.z() + layout.width() / 2.0;
        return viewer.distanceToSqr(cx, cy, cz) <= VIEW_RANGE * VIEW_RANGE;
    }

    /**
     * Log what appeared and what went away since last tick — and nothing at all when the set is
     * unchanged, which is almost every tick.
     *
     * <p><b>Both directions log.</b> The block mirror this feature is modelled on had one direction
     * instrumented and one silent, and the bug that took longest to find was in the silent one. A
     * puppet's two directions fail differently — a twin→carriage puppet has a plot conversion and a
     * moving pose to get wrong, a carriage→twin one does not — so the direction is in the line.</p>
     */
    private static void logTransitions(int carriageIndex, Map<Integer, String> now) {
        Map<Integer, String> before = LIVE.getOrDefault(carriageIndex, Map.of());
        if (before.isEmpty() && now.isEmpty()) return;

        for (Map.Entry<Integer, String> e : now.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                LOGGER.info("[DungeonTrain] Portal puppet spawned: carriage={} key={} {}",
                    carriageIndex, e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<Integer, String> e : before.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                LOGGER.info("[DungeonTrain] Portal puppet removed: carriage={} key={} {}",
                    carriageIndex, e.getKey(), e.getValue());
            }
        }

        if (now.isEmpty()) {
            LIVE.remove(carriageIndex);
        } else {
            LIVE.put(carriageIndex, now);
        }
    }

    /** Human-readable "what, and which way across" for the spawn/despawn lines. */
    private static String label(PortalFrames frames, Entity source) {
        int from = frames.frameAt(source.getX(), source.getY(), source.getZ());
        String direction = from == PortalFrames.FRAME_CARRIAGE ? "CARRIAGE→TWIN" : "TWIN→CARRIAGE";
        String who = source instanceof ServerPlayer player
            ? "player " + player.getGameProfile().getName()
            : String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()));
        return who + " " + direction;
    }
}
