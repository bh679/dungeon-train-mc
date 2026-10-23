package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.data.SaveFreePlayProbe;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One player in Free Play puts <b>everyone on the world</b> in Free Play (Brennan's rule). Items move
 * between players, so a per-player taint leaks: whoever confirmed {@code /give} could hand the loot to
 * a friend whose stats still count.
 *
 * <p>The per-player permanent flag ({@code RUN_CHEATED}, set by {@link RunIntegrity#markCheated})
 * stays as the record of what that player did. This class adds its world-wide shadow: the first time
 * any player's run is marked, the world is marked too, on {@link DungeonTrainWorldData}, written once
 * and never cleared, the same shape as {@link KeepInventoryIntegrity} and {@link PortalTuningIntegrity}.
 * It holds after that player logs off and reaches anyone who joins later. A new run is a new world
 * with fresh world data, so it starts clean exactly as the per-player flag does.</p>
 *
 * <p>Worlds saved before this existed are backfilled once at load: if any saved player already
 * carries the per-player flag, the world is marked ({@link SaveFreePlayProbe#anyPlayerRunCheated}).</p>
 *
 * <p>Like the other world taints, the flag is mirrored into a static so the ~20 persistence gates that
 * call {@link RunIntegrity#isCheated} never touch SavedData on a hot path.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerFreePlayIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mirror of the loaded world's flag. False until an overworld is loaded, and after it stops. */
    private static volatile boolean worldTainted = false;

    /** Who first put the world into Free Play; empty when unknown (a backfilled world). */
    private static volatile String sourceName = "";

    /** What they did; {@code null} when unknown. */
    @Nullable
    private static volatile Component sourceCause = null;

    private PlayerFreePlayIntegrity() {}

    /** True when a player on this world has gone Free Play, so the whole world is Free Play. */
    public static boolean isWorldFreePlay() {
        return worldTainted;
    }

    /** Is {@code player} the one who put the world into Free Play? Their own cause already explains it. */
    public static boolean isSource(ServerPlayer player) {
        return !sourceName.isEmpty() && sourceName.equals(player.getGameProfile().getName());
    }

    /**
     * The cause shown to everyone else: who went Free Play and why, or a generic line when the world
     * was backfilled from older saves and nobody knows.
     */
    public static Component cause() {
        if (sourceName.isEmpty()) {
            return Component.translatable("chat.dungeontrain.free_play.cause.player_unknown");
        }
        return thirdPerson(sourceName, sourceCause);
    }

    /**
     * Retell {@code source}'s own cause for everyone else. The recorded phrases speak to the player
     * who acted ("You used /give."), so the ones that name an action are rebuilt around the player's
     * name; anything else (an operator stamp, a changed config, an unknown cause) falls back to
     * naming who without what. Package-private for unit tests.
     */
    static Component thirdPerson(String name, @Nullable Component cause) {
        if (cause != null && cause.getContents() instanceof TranslatableContents tc && tc.getArgs().length == 1) {
            Object what = tc.getArgs()[0];
            switch (tc.getKey()) {
                case "chat.dungeontrain.free_play.cause.command",
                     "chat.dungeontrain.free_play.cause.creative_mod" -> {
                    return Component.translatable("chat.dungeontrain.free_play.cause.player_used", name, what);
                }
                case "chat.dungeontrain.free_play.cause.gamemode" -> {
                    return Component.translatable("chat.dungeontrain.free_play.cause.player_switched", name, what);
                }
                default -> { }
            }
        }
        return Component.translatable("chat.dungeontrain.free_play.cause.player", name);
    }

    /**
     * {@code source}'s run has just gone Free Play for good: mark the world, then bring everyone else
     * online into line, with the badge, the Ender Chest lock, and a chat line naming who and why.
     * Called from {@link RunIntegrity#markCheated}; no-op once the world is already marked.
     *
     * @param wasVisible whether the session was already visibly Free Play before this, in which case
     *                   everyone has been told once already and the chat line is skipped
     */
    static void markWorld(ServerPlayer source, Component cause, boolean wasVisible) {
        if (worldTainted) return;
        MinecraftServer server = source.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        String name = source.getGameProfile().getName();
        // Write through to the save now, so a crash before the next autosave can't lose it.
        DungeonTrainWorldData.get(overworld).markPlayerFreePlay(name, toJson(cause, server.registryAccess()));
        sourceName = name;
        sourceCause = cause;
        worldTainted = true;
        LOGGER.info("[DungeonTrain] {} went Free Play — this whole world is now Free Play.", name);
        announce(server, source, wasVisible);
    }

    private static void announce(MinecraftServer server, ServerPlayer source, boolean wasVisible) {
        Component cause = cause();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == source) continue; // markCheated / its caller handle the player who did it
            try {
                RunIntegrity.applyFreePlayEffect(player);
                // The Ender Chest slot provider reads RunIntegrity.isCheated live, and the answer for
                // this player has just changed; swap their live chest over now.
                EnderChestLockBridge.engage(player);
                if (!wasVisible && !RunIntegrity.isPermanentlyCheated(player)) {
                    RunIntegrity.sendFreePlayNotice(player, cause);
                }
            } catch (Exception e) {
                // One player's failed swap must not skip everyone after them.
                LOGGER.warn("[DungeonTrain] World-wide Free Play transition failed for {}",
                    player.getName().getString(), e);
            }
        }
    }

    /**
     * Mirror the saved flag at overworld load, and backfill it once for a world saved before Free Play
     * went world-wide. {@code HIGH}, matched to {@link KeepInventoryIntegrity#onOverworldLoad}, so the
     * answer is right before any player joins.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;
        MinecraftServer server = overworld.getServer();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.isFreePlayBackfillDone()) {
            if (!data.isPlayerFreePlay()
                    && SaveFreePlayProbe.anyPlayerRunCheated(server.getWorldPath(LevelResource.ROOT))) {
                data.markPlayerFreePlay("", "");
                LOGGER.info("[DungeonTrain] A saved player on this world was already Free Play — "
                    + "marking the whole world Free Play (one-time backfill).");
            }
            data.markFreePlayBackfillDone();
        }
        worldTainted = data.isPlayerFreePlay();
        sourceName = data.playerFreePlayName();
        sourceCause = fromJson(data.playerFreePlayCauseJson(), server.registryAccess());
        if (worldTainted) {
            LOGGER.info("[DungeonTrain] A player on this world went Free Play{} — the whole world is Free Play.",
                sourceName.isEmpty() ? "" : " (" + sourceName + ")");
        }
    }

    /** A second world in the same game session must not inherit the first world's answer. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        worldTainted = false;
        sourceName = "";
        sourceCause = null;
    }

    private static String toJson(Component cause, HolderLookup.Provider registries) {
        try {
            return Component.Serializer.toJson(cause, registries);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't save the world Free Play cause: {}", e.toString());
            return "";
        }
    }

    @Nullable
    private static Component fromJson(String json, HolderLookup.Provider registries) {
        if (json == null || json.isEmpty()) return null;
        try {
            return Component.Serializer.fromJson(json, registries);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read the world Free Play cause: {}", e.toString());
            return null;
        }
    }
}
