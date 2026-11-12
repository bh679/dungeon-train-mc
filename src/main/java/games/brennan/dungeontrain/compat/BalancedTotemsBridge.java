package games.brennan.dungeontrain.compat;

import games.brennan.balancedtotems.api.BalancedTotemsApi;
import games.brennan.dungeontrain.event.PlayerJoinEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalRoomRescue;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Lands a Void Totem's save back on the train. Balanced Totems asks {@link BalancedTotemsApi}'s rescue
 * target where to put a player falling out of the world, and teleports them there itself.
 *
 * <p>The spot is the front pad nearest the player's X, found by
 * {@link PlayerJoinEvents#findNearestFrontPadTarget} skipping portal groups, exactly as {@code /dtp}
 * lands a player ({@code DtpPlacementService#placeOnDeck}). Off a front pad the train carries you onto
 * the group's own carriages rather than into the gap behind it.</p>
 *
 * <p>Balanced Totems does the teleport, {@code stopRiding} and the fall-distance reset. What it cannot
 * know is that the deck is a Sable sub-level the client may not have yet, so on a hit this also sends
 * the {@link SpawnDeckHoldPacket} and the short harmless window every other deck placement gives
 * ({@link PortalRoomRescue#holdHarmless}). The callback runs just before that teleport.</p>
 *
 * <p>No settled train in the player's dimension (culled, or a world with none) answers empty, and
 * Balanced Totems falls back to the player's last safe ground.</p>
 *
 * <p>The hard reference to {@code BalancedTotemsApi} lives only in {@link #install()}; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches {@link Throwable}.</p>
 */
public final class BalancedTotemsBridge {

    private BalancedTotemsBridge() {}

    public static void install() {
        BalancedTotemsApi.setRescueTarget(BalancedTotemsBridge::deckSpot);
    }

    private static Optional<Vec3> deckSpot(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(player.server.overworld());
        PlayerJoinEvents.FlatbedTarget deck = PlayerJoinEvents.findNearestFrontPadTarget(
            level, data, player.getX(), pIdx -> PortalCarriageSelection.isPortalGroup(level, pIdx));
        if (deck == null) return Optional.empty();

        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
        PortalRoomRescue.holdHarmless(player, player.server.overworld().getGameTime());
        return Optional.of(new Vec3(deck.x(), deck.y(), deck.z()));
    }
}
