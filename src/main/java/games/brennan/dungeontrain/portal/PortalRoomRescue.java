package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.PlayerJoinEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * The last way out of a room whose train is gone: put the player back on the train.
 *
 * <h2>Why this exists</h2>
 * <p>Everything else about a pocket room's way back is about keeping the crossing seamless — the
 * train stands still while somebody is inside it ({@link PortalTrainFreeze}), its carriage group is
 * held loaded ({@link PortalPairResidency}), and a group culled to Sable holding is snatched back
 * ({@link PortalCarriageRevival}). Behind all three sits one state none of them can fix: the group is
 * neither resident nor in holding, so there is nothing to bring back and no corridor in the room leads
 * anywhere.</p>
 *
 * <p>That state used to end in a message — <i>"This room has lost its train. Its corridors lead
 * nowhere."</i> — which is an accurate description of a player sealed in a room that repeats forever
 * with no way out but dying. Being accurate is not the same as being acceptable. A teleport is a worse
 * way to leave a room than walking out of a corridor, and a much better one than not leaving.</p>
 *
 * <h2>Built from the pieces that already put players on trains</h2>
 * <p>{@code /dtp} and the login placement both face the same problem — drop a player onto a moving
 * train from somewhere else entirely — and solved it in {@code DtpPlacementService.tryFinish}. This
 * follows it rather than re-deriving it, which matters most for the part that is not obvious: the
 * {@link SpawnDeckHoldPacket}. A flatbed deck is a Sable sub-level, and a player teleported onto one
 * arrives a few ticks before the client has it; without the hold they fall through the deck they were
 * just rescued onto.</p>
 */
public final class PortalRoomRescue {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Facing on arrival, matching {@code /dtp}'s: along the train rather than across it. */
    private static final float ARRIVAL_YAW = -90.0f;

    private PortalRoomRescue() {}

    /**
     * Put {@code player} back on the train, or report that there is no train to put them on.
     *
     * <p>Lands on the settled deck nearest the <b>structure's</b> world X rather than the player's.
     * A room is stamped in the chunk columns its carriage occupied when the player walked in, so that
     * is where the train was when they left it — the nearest deck to it is the part of the train they
     * came off, not whichever end happens to be loaded.</p>
     *
     * @return {@code false} when no carriage group has settled anywhere, so the caller can say so and
     *         try again rather than teleporting somebody into empty air
     */
    public static boolean returnToTrain(ServerLevel level, ServerPlayer player,
                                        PortalStructure structure, int pairKey) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        PlayerJoinEvents.FlatbedTarget deck =
            PlayerJoinEvents.findNearestFlatbedTarget(level, data, structure.origin().getX());
        if (deck == null) return false;

        double fromX = player.getX(), fromY = player.getY(), fromZ = player.getZ();
        player.teleportTo(level, deck.x(), deck.y(), deck.z(), ARRIVAL_YAW, 0.0f);
        // The deck is a moving sub-level the client does not have yet. Without this the player drops
        // through the floor they were just put on — see the class javadoc.
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));

        player.displayClientMessage(
            Component.translatable("chat.dungeontrain.portal.returned").withStyle(ChatFormatting.GRAY),
            false);

        // WARN rather than INFO: this is a rescue from a state the freeze and the force-load ticket
        // are supposed to make unreachable, so every occurrence is worth finding in a log afterwards.
        LOGGER.warn("[DungeonTrain] Portal pair {} lost its carriage group entirely — returned {} to "
                + "the train at ({}, {}, {}), from ({}, {}, {}) inside the room.",
            pairKey, player.getName().getString(),
            fmt(deck.x()), fmt(deck.y()), fmt(deck.z()), fmt(fromX), fmt(fromY), fmt(fromZ));
        return true;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
