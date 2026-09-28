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
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;
import org.joml.Vector3dc;
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
     * <p>Sized to draw an authored room whole. {@link PortalRoomMobs#MAX_LIVE_PER_STRUCTURE} lets a
     * room stand up 64 mobs by design, and a player reported what a smaller cap did with that — a
     * horde past it went <i>invisible</i> while staying entirely real and still hitting them. The
     * budget covers those 64 plus the players in the corridor and the loot a fight in it drops.</p>
     *
     * <p>It used to be sixteen, and the reason was the packet: every puppet re-sent its whole
     * description — synched data and five item stacks — every tick, so the cap was a bandwidth cap.
     * {@link PortalPuppetDelta} sends the description once and a pose, or nothing, thereafter, so
     * what this number bounds now is the client's render work for the room, which is the same work
     * the other copy already asks of it for the real mobs. Above it, {@link #select} still decides
     * who gets a slot and keeps that set stable tick to tick.</p>
     */
    public static final int MAX_PER_PAIR = 96;

    /** Ordering tiers. A lower tier spends the budget first; see {@link #tierOf}. */
    static final int TIER_PLAYER = 0;
    static final int TIER_LIVING = 1;
    static final int TIER_SCENERY = 2;

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

    /**
     * Carriage index → how many puppets that pair dropped when the count last changed.
     *
     * <p>The drop is worth telling a room author about, but it is a <i>standing condition</i>, not
     * an event: a corridor over the cap is over it on every one of the twenty ticks a second it
     * stays that way. Logged unconditionally it produced 1033 identical INFO lines a minute on the
     * server thread in the report this map exists because of. Keyed like {@link #LIVE} so the count
     * is forgotten with the pair rather than outliving it.</p>
     */
    private static final Map<Integer, Integer> DROPPED = new HashMap<>();

    /**
     * Viewer → puppet key → what that viewer was last sent for it.
     *
     * <p>This is the server's picture of each client's {@code PortalPuppetsClient} map, and it is
     * what lets a snapshot say "still there, hasn't moved" in three bytes instead of re-describing
     * the puppet. Kept in step with {@link #SENT}: a viewer sent the clearing snapshot has their
     * client emptied, so their memory goes with it, and a key a snapshot leaves out is dropped by
     * the client and so is dropped here.</p>
     */
    private static final Map<UUID, Map<Integer, PortalPuppetDelta.Sent>> KNOWN = new HashMap<>();

    /** Pairs whose grid-snap residual has been logged; see {@link #poseAligned}. */
    private static final Set<Integer> ALIGN_LOGGED = new HashSet<>();

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

        /**
         * Queue {@code full} for {@code viewer}, cut down to what their client is missing.
         *
         * <p>The memory is advanced here, at queueing, rather than at dispatch: the same tick can
         * queue a viewer two pairs' worth of entries, and nothing about the shape of one depends on
         * the other, so there is no reason to wait.</p>
         */
        private void add(ServerPlayer viewer, PortalPuppetsPacket.Entry full, long tick) {
            Map<Integer, PortalPuppetDelta.Sent> known =
                KNOWN.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>());
            PortalPuppetDelta.Sent sent = known.get(full.key());

            PortalPuppetsPacket.Entry entry = PortalPuppetDelta.classify(sent, full, tick);
            known.put(full.key(), sent == null
                ? new PortalPuppetDelta.Sent(full, tick)
                : sent.advance(entry, tick));

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
                    forgetUnsent(player.getUUID(), entries);
                } else if (SENT.remove(player.getUUID())) {
                    DungeonTrainNet.sendTo(player, PortalPuppetsPacket.empty());
                    KNOWN.remove(player.getUUID());
                }
            }

            // A player who logged out or changed dimension mid-corridor is no longer in this list, so
            // the branch that would have forgotten them never runs and their id would sit here for
            // the rest of the session. Their client is covered either way — it drops puppets on its
            // own if the snapshots stop — but the set should not grow without bound.
            if (SENT.size() > players.size() || KNOWN.size() > players.size()) {
                Set<UUID> here = new HashSet<>();
                for (ServerPlayer player : players) here.add(player.getUUID());
                SENT.retainAll(here);
                KNOWN.keySet().retainAll(here);
            }
        }

        /**
         * Drop from a viewer's memory every key this snapshot did not name — the client drops the
         * puppet on the same rule, and a key that later returns must be described afresh.
         */
        private static void forgetUnsent(UUID viewer, List<PortalPuppetsPacket.Entry> entries) {
            Map<Integer, PortalPuppetDelta.Sent> known = KNOWN.get(viewer);
            if (known == null || known.size() == entries.size()) return;
            Set<Integer> named = new HashSet<>();
            for (PortalPuppetsPacket.Entry entry : entries) named.add(entry.key());
            known.keySet().retainAll(named);
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
        // Who this pair is drawing for, resolved once. The same set answers both questions the rest
        // of this method asks — which entities are nearest, and who receives them — so the two
        // cannot disagree about a viewer that stepped out of range between them.
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (canSee(frames, player)) viewers.add(player);
        }

        List<PortalPuppetsPacket.Entry> entries = new ArrayList<>();
        Map<Integer, String> live = new HashMap<>();

        // Rank before spending the budget, rather than taking the level's own scan order. Measuring
        // every candidate is cheap — a distance and an instanceof — where describing one is not, so
        // the sixteen that survive are chosen from the whole room and only they are described.
        // Ranked by index into the caller's list rather than by holding the entities twice; select
        // does not reorder that list, so the indices stay good.
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < occupants.size(); i++) {
            Entity source = occupants.get(i);
            if (!eligible(source)) continue;
            candidates.add(new Candidate(source.getId(), i, tierOf(source),
                nearestViewerDistSq(viewers, source)));
        }

        List<Candidate> chosen = select(candidates, MAX_PER_PAIR);

        // One pose-aligned frame for the whole pair this tick; see poseAligned.
        PortalFrames aligned = poseAligned(frames, ship, carriageIndex);

        for (Candidate candidate : chosen) {
            Entity source = occupants.get(candidate.index());

            PortalPuppetsPacket.Entry entry = describe(aligned, ship, source);
            if (entry == null) continue;

            if (PortalPuppetTrace.isEnabled()) trace(carriageIndex, frames, aligned, ship, source, entry);

            entries.add(entry);
            live.put(entry.key(), label(aligned, source));
        }

        logDropped(carriageIndex, candidates.size() - chosen.size());
        logTransitions(carriageIndex, live);

        if (entries.isEmpty()) return;

        long tick = level.getGameTime();
        for (ServerPlayer viewer : viewers) {
            for (PortalPuppetsPacket.Entry entry : entries) {
                // Never your own stand-in. Filtered per recipient rather than hidden client-side, so
                // a player's puppet is not merely invisible to them — it never reaches them.
                if (entry.key() == viewer.getId()) continue;
                session.add(viewer, entry, tick);
            }
        }
    }

    /**
     * One entity's claim on the budget: what it is, how far off it is, and where to find it again.
     *
     * <p>A plain value so {@link #select} — the part with the policy in it — can be tested without
     * a level to put entities in. {@code index} points back into the caller's source list;
     * {@code id} is the entity id, which is stable across ticks and is what breaks ties.</p>
     */
    record Candidate(int id, int index, int tier, double distSq) {}

    /**
     * The candidates worth describing, best first, at most {@code max} of them.
     *
     * <p><b>Tier before distance.</b> Scenery — a dropped sword, an experience orb, a painting — is
     * as physically in the room as a zombie is, and in a quiet corridor it should be drawn. But it
     * must never be drawn <i>instead</i> of a mob: the reported bug is a player clearing a horde
     * whose own loot piled up in the corridor and took the budget from the zombies still swinging
     * at them. Loot now fills only the slots the mobs did not want.</p>
     *
     * <p><b>Then distance, then id.</b> Distance is what makes a truncated snapshot the right
     * sixteen rather than sixteen arbitrary ones. The id tiebreak is what makes it the <i>same</i>
     * sixteen next tick: ordering that fell through to the level's scan order reshuffled every
     * tick, and a puppet dropped and re-added is a render model torn down and rebuilt — 142 of them
     * in a minute, each a fresh mob with its brain and goals, on the render thread.</p>
     */
    static List<Candidate> select(List<Candidate> candidates, int max) {
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.<Candidate>comparingInt(Candidate::tier)
            .thenComparingDouble(Candidate::distSq)
            .thenComparingInt(Candidate::id));
        return ranked.size() <= max ? ranked : new ArrayList<>(ranked.subList(0, max));
    }

    /** Which claim on the budget this entity has. See {@link #select}. */
    static int tierOf(Entity entity) {
        if (entity instanceof ServerPlayer) return TIER_PLAYER;
        if (entity instanceof LivingEntity) return TIER_LIVING;
        return TIER_SCENERY;
    }

    /**
     * How far this entity is from the nearest player being drawn for.
     *
     * <p>Nearest rather than per-viewer: entries are described once for the pair and handed to
     * every viewer, so sorting per viewer would mean describing the room once per player and
     * multiplying the cost this ordering exists to bound. With nobody in range every candidate
     * scores the same and the tier and id decide.</p>
     */
    private static double nearestViewerDistSq(List<ServerPlayer> viewers, Entity entity) {
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer viewer : viewers) {
            nearest = Math.min(nearest, viewer.distanceToSqr(entity));
        }
        return nearest;
    }

    /**
     * The pair's frames with the carriage origin read off the ship's <i>pose</i>, not its bounding box.
     *
     * <p>The carriage origin the tick hands in comes from {@code ship.worldAABB()}, and Sable's box
     * lags its logical pose by a fraction of a block that changes tick to tick. Everything else a
     * puppet's position goes through — {@code worldToShip} here, the carry that moves a rider on the
     * server — reads the pose. Mixing the two puts the lag into the corridor-local offset, so a
     * twin-side zombie's plot-local coordinates wobble on the carriage and a carriage-side rider's
     * twin puppet wobbles at the world floor, both against a floor that is standing perfectly still
     * from the viewer's point of view. That is the shimmer a moving train shows.</p>
     *
     * <p>The corridor is stamped block-aligned in plot space, so the box-derived origin, taken into
     * plot space, lands within the lag of an integer corner. Rounding recovers the exact corner and
     * the pose puts it back in the world — a point that moves with the pose and only with the pose.
     * Scoped to puppets: the swap and facing logic keep the box-derived frame and the hysteresis
     * that was sized for it.</p>
     *
     * <p>Logged once per pair with the residual the rounding removed, so a corridor whose origin is
     * not on the grid — where rounding would introduce a constant offset rather than remove a
     * jitter — shows itself in the log as a residual near a half block.</p>
     */
    public static PortalFrames poseAligned(PortalFrames frames, ManagedShip ship, int carriageIndex) {
        PortalFrames.Origin o = frames.originOf(PortalFrames.FRAME_CARRIAGE);
        Vector3d plot = ship.worldToShip(new Vector3d(o.x(), o.y(), o.z()));
        double rx = Math.rint(plot.x), ry = Math.rint(plot.y), rz = Math.rint(plot.z);

        if (ALIGN_LOGGED.add(carriageIndex)) {
            LOGGER.info("[DungeonTrain] Portal puppet frame for carriage {} snapped to plot grid — "
                    + "residual ({}, {}, {})",
                carriageIndex, fmt(plot.x - rx), fmt(plot.y - ry), fmt(plot.z - rz));
        }

        Vector3d world = ship.shipToWorld(new Vector3d(rx, ry, rz));
        return new PortalFrames(frames.layout(),
            new PortalFrames.Origin(world.x, world.y, world.z), frames.twin(), frames.role());
    }

    /**
     * One line per described puppet per tick, for {@code /dungeontrain debug puppet-trace}.
     *
     * <p>Everything a wobble could hide in, side by side: where the source is, where it was sent
     * to and in which space, and the two readings of the carriage origin — box and pose — so a
     * varying gap between them, or a source that is itself moving, shows in the numbers.</p>
     */
    private static void trace(int carriageIndex, PortalFrames box, PortalFrames aligned, ManagedShip ship,
                              Entity source, PortalPuppetsPacket.Entry entry) {
        PortalFrames.Origin b = box.originOf(PortalFrames.FRAME_CARRIAGE);
        PortalFrames.Origin a = aligned.originOf(PortalFrames.FRAME_CARRIAGE);
        Vector3dc pose = ship.currentWorldPosition();
        LOGGER.info("[DungeonTrain][puppet] c={} key={} {} src=({}, {}, {}) sent=({}, {}, {}) {} "
                + "boxOrigin=({}, {}, {}) poseOrigin=({}, {}, {}) pose=({}, {}, {}) onGround={} dm=({}, {}, {})",
            carriageIndex, entry.key(), label(aligned, source),
            fmt(source.getX()), fmt(source.getY()), fmt(source.getZ()),
            fmt(entry.x()), fmt(entry.y()), fmt(entry.z()), entry.isPlotSpace() ? "PLOT" : "WORLD",
            fmt(b.x()), fmt(b.y()), fmt(b.z()), fmt(a.x()), fmt(a.y()), fmt(a.z()),
            fmt(pose.x()), fmt(pose.y()), fmt(pose.z()), source.onGround(),
            fmt(source.getDeltaMovement().x), fmt(source.getDeltaMovement().y), fmt(source.getDeltaMovement().z));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Drop a pair's puppets — it is out of range, or nobody is in it any more. */
    public static void forget(int carriageIndex) {
        logTransitions(carriageIndex, Map.of());
        LIVE.remove(carriageIndex);
        DROPPED.remove(carriageIndex);
        ALIGN_LOGGED.remove(carriageIndex);
    }

    /** Forget everything, for a world unload or a server stop. */
    public static void clear() {
        LIVE.clear();
        SENT.clear();
        DROPPED.clear();
        KNOWN.clear();
        ALIGN_LOGGED.clear();
    }

    /**
     * Say how many puppets the cap turned away, when that number changes and not otherwise.
     *
     * <p>The line is aimed at whoever authored the room — it is the signal to lower a mob cell's
     * weight — so it stays at INFO where they will see it. What it must not do is repeat: see
     * {@link #DROPPED}.</p>
     */
    private static void logDropped(int carriageIndex, int dropped) {
        if (dropped <= 0) {
            DROPPED.remove(carriageIndex);
            return;
        }

        Integer before = DROPPED.get(carriageIndex);
        if (before != null && before == dropped) return;

        DROPPED.put(carriageIndex, dropped);
        LOGGER.info("[DungeonTrain] Portal puppets capped at {} for carriage {} — {} not described",
            MAX_PER_PAIR, carriageIndex, dropped);
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
        if (entity.isPassenger() || entity.isSpectator()) return false;
        if (entity.isInvisible()) return false;
        // A mob keeps its puppet through its death: vanilla holds the entity for twenty ticks after
        // its health hits zero to play the fall-over, and a puppet that vanished the moment isAlive
        // went false blinked out mid-swing while the real one was still on its way down.
        if (!entity.isAlive()) {
            return entity instanceof LivingEntity living && living.isDeadOrDying() && !entity.isRemoved();
        }
        return true;
    }

    /** One entity's puppet, or {@code null} if it turns out not to be in either corridor. */
    private static PortalPuppetsPacket.Entry describe(PortalFrames frames, ManagedShip ship,
                                                      Entity source) {
        PortalFrames.Move dest = frames.mirror(source.getX(), source.getY(), source.getZ());
        if (dest == null) return null;

        // The local Y carries across verbatim. This used to snap a grounded entity to the corridor's
        // floor surface, because the box-derived carriage origin put the two block grids a fraction
        // apart and a verbatim offset stood a puppet a little inside or above the floor. That hid
        // the lag at the cost of flattening the room: anything standing on a stair, a trapdoor or a
        // slab was drawn a block low, inside what it stood on. The frame is pose-aligned now (see
        // poseAligned), so the grids coincide exactly and the source's own height is the right one.
        double y = dest.y();

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

        return PortalPuppetsPacket.Entry.full(
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
            living == null ? ItemStack.EMPTY : living.getItemBySlot(EquipmentSlot.FEET).copy(),
            // The hit flash and the fall-over. Neither is synched data — vanilla sends them as
            // entity events — so they ride here as two bytes, and the puppet flinches and dies
            // in step with the mob it stands for.
            living == null ? 0 : (byte) Math.min(living.hurtTime, Byte.MAX_VALUE),
            living == null ? 0 : (byte) Math.min(living.deathTime, Byte.MAX_VALUE));
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
