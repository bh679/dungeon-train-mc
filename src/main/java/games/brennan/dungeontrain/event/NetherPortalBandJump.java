package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.mixinguard.MixinGuardReport;
import games.brennan.dungeontrain.compat.mixinguard.ThirdPartyMixinTargets;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalLoadScreenPacket;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.world.NetherPortalLinks;
import games.brennan.dungeontrain.world.OthersideExitPortal;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.NetherPortalJump;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A Nether portal on the overworld ride is a portal <em>along the track</em>. A portal that already has a
 * partner ({@link NetherPortalLinks}) takes the player back to it exactly, like a vanilla pair; any other
 * portal lets the player out of a new portal at the proportional spot in the destination band's core
 * ({@link NetherPortalJump}: first overworld → first Nether; any later non-Nether band → the previous
 * Nether; from the Nether → the band before it), {@link #SIDE_OFFSET} blocks off to the side of the track
 * so it never sits on the rails or in a tunnel. Everything else is vanilla: the normal time standing in
 * the portal, the exit portal found or built by {@code PortalForcer}, the portal sound and chunk ticket,
 * no train respawn, no chat. A frame built on a carriage stands in the ship plot grid, so it is never
 * paired and no trip ever lands there ({@link #chooseExit}). Called from {@code mixin/NetherPortalBlockBandJumpMixin} at the head of
 * {@code NetherPortalBlock#getPortalDestination}, which hands the returned same-level transition to
 * vanilla's own {@code Entity#handlePortal} → {@code changeDimension}.
 *
 * <p>Untouched (vanilla portal into the real Nether): any dimension but the overworld — the bands are an
 * overworld construct — every non-player entity, worlds without the auto-train, and the
 * {@code netherPortalBandJump} / {@code netherTransitionEnabled} toggles being off.</p>
 *
 * <p>Because a same-dimension teleport never shows vanilla's "Loading terrain" screen, the player is
 * sent {@link PortalLoadScreenPacket} just before the transition so the client covers the chunk stream
 * with the Nether-portal loading screen instead of a view of empty space.</p>
 */
public final class NetherPortalBandJump {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks sideways from the track centre a new exit portal is asked for — clear of the rails and tunnels. */
    static final int SIDE_OFFSET = 48;

    /** Vanilla's private {@code NetherPortalBlock#getExitPortal}, reached through the mixin invoker. */
    @FunctionalInterface
    public interface ExitPortalFinder {
        @Nullable
        DimensionTransition find(ServerLevel level, Entity entity, BlockPos portalPos, BlockPos exitPos,
                                 boolean isNether, WorldBorder border);
    }

    /** Which portal is being used: vanilla's Nether portal, or Deeper Darker's Otherside portal. */
    public enum Kind {
        NETHER, OTHERSIDE;

        NetherPortalJump.Target target() {
            return this == OTHERSIDE ? NetherPortalJump.Target.DEEPER_DARK : NetherPortalJump.Target.NETHER;
        }
    }

    /** {@code handled == false} → let vanilla run; otherwise {@code transition} (possibly null = nothing happens) is the answer. */
    public record Outcome(boolean handled, @Nullable DimensionTransition transition) {
        static final Outcome VANILLA = new Outcome(false, null);
    }

    /** The Otherside mixin, by the name {@link MixinGuardReport} and {@code /dungeontrain debug mixin-guards} use. */
    static final String OTHERSIDE_MIXIN =
            ThirdPartyMixinTargets.MIXIN_PACKAGE + "deeperdarker.OthersidePortalBlockBandJumpMixin";

    private static final LogFirstN OTHERSIDE_LINK_FAILURE = new LogFirstN(1);

    private NetherPortalBandJump() {}

    /**
     * {@link #destination} for Deeper Darker's Otherside portal, from {@code mixin/deeperdarker/
     * OthersidePortalBlockBandJumpMixin}. {@code OthersideExitPortal} calls Deeper Darker's own classes; a newer Deeper
     * Darker build that moved one of them throws a {@link LinkageError} here instead of at boot (the mixin plugin only
     * checks what {@code ThirdPartyMixinTargets} lists). That is caught, logged once, recorded as the mixin being off,
     * and the trip is left to the mod: its own {@code getPortalDestination} runs and the player reaches the real
     * Otherside.
     */
    public static Outcome othersideDestination(ServerLevel level, Entity entity, BlockPos portalPos) {
        return guardedOtherside(() -> destination(level, entity, portalPos, Kind.OTHERSIDE, OthersideExitPortal::find),
                MixinGuardReport::recordSkipped);
    }

    /**
     * The catch rule of {@link #othersideDestination}: a {@link LinkageError} means vanilla, after {@code recordSkipped}
     * is told the mixin is off; anything else propagates.
     */
    static Outcome guardedOtherside(Supplier<Outcome> trip, Consumer<String> recordSkipped) {
        try {
            return trip.get();
        } catch (LinkageError e) {
            recordSkipped.accept(OTHERSIDE_MIXIN);
            OTHERSIDE_LINK_FAILURE.warn(LOGGER, "[DungeonTrain] Otherside portal band-jump is off: this Deeper Darker "
                    + "build no longer has something DT's OthersidePortalBlockBandJumpMixin uses. Instead: Otherside "
                    + "portals lead to Deeper Darker's real Otherside. Re-check the deeperdarker pin in gradle.properties", e);
            return Outcome.VANILLA;
        }
    }

    /** Resolve {@code entity} entering the portal at {@code portalPos} in {@code level}. */
    public static Outcome destination(ServerLevel level, Entity entity, BlockPos portalPos, ExitPortalFinder finder) {
        return destination(level, entity, portalPos, Kind.NETHER, finder);
    }

    /**
     * {@link #destination(ServerLevel, Entity, BlockPos, ExitPortalFinder)} for a portal of kind {@code kind}. An
     * Otherside portal leads to the Deeper Dark passes as a Nether portal leads to the Nether ones, with its own
     * pairs ({@link NetherPortalLinks#getOtherside}).
     */
    public static Outcome destination(ServerLevel level, Entity entity, BlockPos portalPos, Kind kind,
                                      ExitPortalFinder finder) {
        if (level.dimension() != Level.OVERWORLD) return Outcome.VANILLA;
        if (!(entity instanceof ServerPlayer player)) return Outcome.VANILLA;
        if (!DungeonTrainCommonConfig.isNetherPortalBandJumpEnabled()) return Outcome.VANILLA;
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return Outcome.VANILLA;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return Outcome.VANILLA;

        String who = player.getName().getString();
        int fromX = player.getBlockX();
        NetherPortalLinks links = kind == Kind.OTHERSIDE ? NetherPortalLinks.getOtherside(level) : NetherPortalLinks.get(level);
        // A frame built on a carriage sits in the ship plot grid, millions of blocks out: its position is
        // never a place to send anyone, and it moves with the train anyway, so it is never paired.
        boolean onCarriage = Shipyards.isInPlotGrid(level, portalPos);
        BlockPos origin = NetherPortalLinks.frameKey(level, portalPos);
        BlockPos partner = links.partnerOf(level, origin);
        ExitChoice choice = chooseExit(partner != null, onCarriage,
            partner != null && Shipyards.isInPlotGrid(level, partner));
        if (choice == ExitChoice.UNLINK_AND_JUMP) {
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: dropped link {} ↔ {} — a frame on a carriage is never paired",
                fromX, who, origin, partner);
            links.unlink(origin);
        }

        BlockPos exit;
        String why;
        if (choice == ExitChoice.LINKED) {
            exit = partner;
            why = "linked portal";
        } else {
            exit = bandJumpExit(level, data, player, fromX, kind);
            if (exit == null) {
                LOGGER.info("[DungeonTrain] {} portal at x={} by {}: no band to go to — vanilla portal", kind, fromX, who);
                return Outcome.VANILLA;
            }
            why = "to " + BandLabel.bandAt(level, exit.getX()) + " core";
        }

        DimensionTransition transition = finder.find(level, player, portalPos, exit, false, level.getWorldBorder());
        if (transition == null) {
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: no exit portal could be placed near {}", fromX, who, exit);
            return new Outcome(true, null);
        }
        BlockPos arrived = NetherPortalLinks.frameKey(level, BlockPos.containing(transition.pos()));
        if (Shipyards.isInPlotGrid(level, arrived)) {
            // Never land anyone in the plot grid: there is no ground there, only a fall that never ends.
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: exit {} is in the ship plot grid — refused",
                fromX, who, arrived);
            if (choice == ExitChoice.LINKED) links.unlink(origin);
            return new Outcome(true, null);
        }
        if (choice != ExitChoice.LINKED && !onCarriage) links.link(origin, arrived);
        // The band counts as the real Nether (and a Deeper Dark core as the Otherside): fire the criterion vanilla
        // fires on a dimension change, so "We Need to Go Deeper" and the Nether tab — or Deeper Darker's
        // "enter the Otherside" — open just as they would through a real portal.
        (kind == Kind.OTHERSIDE
            ? NetherPortalAdvancements.othersideCrossing(NetherBand.isOthersideAt(level, fromX),
                NetherBand.isOthersideAt(level, arrived.getX()))
            : NetherPortalAdvancements.crossing(isRealNether(level, fromX), isRealNether(level, arrived.getX())))
            .ifPresent(c -> CriteriaTriggers.CHANGED_DIMENSION.trigger(player, c.from(), c.to()));
        LOGGER.info("[DungeonTrain] nether portal: {} x={} ({}) → {} ({}), frames {} ↔ {}{}",
            who, fromX, BandLabel.bandAt(level, fromX), exit, why, origin, arrived, onCarriage ? " onCarriage=true" : "");
        DungeonTrainNet.sendTo(player, new PortalLoadScreenPacket());
        return new Outcome(true, transition);
    }

    /** How a portal trip picks its exit. */
    enum ExitChoice {
        /** Back to the frame's recorded partner. */
        LINKED,
        /** A new exit portal in the destination band's core. */
        BAND_JUMP,
        /** As {@link #BAND_JUMP}, after forgetting a link that should never have been made. */
        UNLINK_AND_JUMP
    }

    /**
     * The exit rule: a recorded partner is followed unless either end is a frame on a carriage (a ship-plot
     * position) — such a link is dropped and the trip band-jumps instead, as it does with no partner.
     */
    static ExitChoice chooseExit(boolean hasPartner, boolean originOnCarriage, boolean partnerOnCarriage) {
        if (!hasPartner) return ExitChoice.BAND_JUMP;
        if (originOnCarriage || partnerOnCarriage) return ExitChoice.UNLINK_AND_JUMP;
        return ExitChoice.LINKED;
    }

    /** Where a new exit portal is asked for in the destination band's core, or null when there is no band to go to. */
    @Nullable
    private static BlockPos bandJumpExit(ServerLevel level, DungeonTrainWorldData data, ServerPlayer player, int fromX,
                                         Kind kind) {
        OptionalLong target = NetherPortalJump.targetX(WorldGenCycle.fromConfig(), x -> BandLabel.bandAt(level, x), fromX,
            kind.target());
        if (target.isEmpty()) return null;
        return level.getWorldBorder().clampToBounds(target.getAsLong(), player.getY(), sideOfTrack(data, player.getZ()));
    }

    /** A Nether band column that is the real Nether, not a Deeper Dark. */
    private static boolean isRealNether(ServerLevel level, int x) {
        return NetherBand.isInNetherBand(level, x) && !NetherBand.isOthersideAt(level, x);
    }

    /** Z for a new exit portal: {@link #SIDE_OFFSET} blocks from the track centre, on the player's side of it. */
    static double sideOfTrack(DungeonTrainWorldData data, double playerZ) {
        int centre = TrackGeometry.from(data.dims(), data.getTrainY()).trackCenterZ();
        return playerZ >= centre ? centre + SIDE_OFFSET : centre - SIDE_OFFSET;
    }
}
