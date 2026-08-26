package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deferred second half of {@code /dungeontrain portal test}.
 * {@link games.brennan.dungeontrain.command.PortalTestCommand} forces the seed group to hold a
 * portal, re-seeds the train and parks the player above the assembly footprint; this waits for the
 * corridor to actually exist, then puts them in front of its door.
 *
 * <p><b>It waits for a deck, not for the corridor's pair.</b> An earlier version held out for the
 * pairing to be published, on the grounds that the corridor is the whole point. That was
 * self-defeating: a twin is stamped on <i>approach</i>, so the pair only goes live once somebody is
 * near the carriage — and waiting for it kept the player parked 48 blocks above the track, which is
 * sometimes near enough and sometimes not. Observed both ways on the same build. Landing first is
 * what makes the pair happen, so that is what this does; the corridor finishes coming alive while
 * the player is walking the few blocks to its door.</p>
 *
 * <p>The landing itself is the one {@link games.brennan.dungeontrain.portal.PortalRoomRescue} uses:
 * the settled flatbed deck nearest the entry carriage, plus a {@link SpawnDeckHoldPacket} — a deck
 * is a moving sub-level the client does not have yet, and without the hold the player falls through
 * the floor they were just put on.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTestPlacementService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks to wait for a settled deck before giving up. 20 TPS, so 100 ≈ 5s — the same window
     * {@link DtpPlacementService} allows, because this now waits on the same thing it does.
     */
    private static final int MAX_RETRY_TICKS = 100;

    /** The forced test group's entry corridor. See {@code PortalTestCommand}. */
    private static final int ENTRY_CARRIAGE_INDEX = 0;

    /** Facing +X, down the corridor and along the train's travel direction. */
    private static final float FACE_EAST = -90.0f;

    private record Pending(ServerLevel trainLevel, double spawnX, int ticks) {}

    /** Player UUID → in-flight test spawn. Cleared on success/timeout/logout. */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalTestPlacementService() {}

    /** Queue {@code player} to be dropped at the test corridor's door once its pair goes live. */
    public static void enqueue(ServerPlayer player, ServerLevel trainLevel, double spawnX) {
        PENDING.put(player.getUUID(), new Pending(trainLevel, spawnX, 0));
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (PENDING.isEmpty()) return;

        MinecraftServer server = level.getServer();
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }

            Pending pending = entry.getValue();
            if (tryFinish(server, player, pending)) {
                it.remove();
                continue;
            }

            int ticks = pending.ticks() + 1;
            if (ticks >= MAX_RETRY_TICKS) {
                timeOut(server, player, pending);
                it.remove();
            } else {
                entry.setValue(new Pending(pending.trainLevel(), pending.spawnX(), ticks));
            }
        }
    }

    /** @return true once the player has been placed — false to retry next tick. */
    private static boolean tryFinish(MinecraftServer server, ServerPlayer player, Pending pending) {
        // Land as soon as a deck has settled, WITHOUT waiting for the corridor's pair to publish.
        //
        // Waiting for it was self-defeating. A twin is stamped on APPROACH, so the pair only goes
        // live once somebody is near the carriage — and this held the player at a holding spot 48
        // blocks above the track while it waited for exactly that. Sometimes the approach range
        // reached them up there and it paired in a few seconds; sometimes it did not and the whole
        // ten-second window expired against a corridor that had been stamped and furnished the
        // entire time. Landing first is what makes the pair happen.
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        PlayerJoinEvents.FlatbedTarget deck = PlayerJoinEvents.findNearestFlatbedTarget(
            pending.trainLevel(), data, pending.spawnX());
        if (deck == null) return false; // train not settled yet — retry next tick

        land(player, pending.trainLevel(), data, deck);
        player.sendSystemMessage(Component.literal(
            "Dimensional carriage ready — the entry corridor is ahead of you. Walk straight in.")
            .withStyle(ChatFormatting.AQUA));

        LOGGER.info("[DungeonTrain] portal test placed {} at the entry corridor's deck ({}, {}, {}), "
                + "pair {}",
            player.getName().getString(), fmt(deck.x()), fmt(deck.y()), fmt(deck.z()),
            PortalPairIndex.get(ENTRY_CARRIAGE_INDEX) == null
                ? "not live yet — it stamps as they approach" : "already live");
        return true;
    }

    /**
     * The train never settled anywhere in the window, so there is no deck to stand on. Release them
     * where they are rather than leaving somebody parked in the sky.
     */
    private static void timeOut(MinecraftServer server, ServerPlayer player, Pending pending) {
        player.setInvulnerable(false);
        player.sendSystemMessage(Component.literal(
            "The train didn't settle in time — you've been left at the holding spot. Try again.")
            .withStyle(ChatFormatting.YELLOW));
        LOGGER.warn("[DungeonTrain] portal test placement timed out for {} after {} ticks — "
                + "no carriage group settled to land on",
            player.getName().getString(), MAX_RETRY_TICKS);
    }

    private static void land(ServerPlayer player, ServerLevel trainLevel, DungeonTrainWorldData data,
                             PlayerJoinEvents.FlatbedTarget deck) {
        // A vehicle would drag them straight back off the deck.
        if (player.isPassenger()) player.stopRiding();
        player.teleportTo(trainLevel, deck.x(), deck.y(), deck.z(), FACE_EAST, 0.0f);
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
        player.setInvulnerable(false);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
