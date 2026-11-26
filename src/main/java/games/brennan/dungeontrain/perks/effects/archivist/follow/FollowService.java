package games.brennan.dungeontrain.perks.effects.archivist.follow;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.archivist.ArchivistPerks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Favourite Author on the server: keeps each player's follows mirrored from the relay, runs the
 * menu's and the Follow buttons' requests, and answers what the book and photo picks need.
 *
 * <p>Follows are kept whatever the perk's rank (Free Play, a death, perks switched off): only the
 * picks read the rank. A player who has not given network consent sends nothing to the relay, so
 * their list stays as it was and lookups answer {@link FollowsSyncPacket.Lookup#UNREACHABLE}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FollowService {

    /** Minecraft player names: what the relay can look up, so nothing else is sent. */
    static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** How often an online player's list is re-read from the relay (its `exhausted` flags move). */
    private static final int REFRESH_TICKS = 20 * 60 * 5;
    /** Least time between two of one player's relay requests. */
    private static final long REQUEST_GAP_MS = 750;

    private record LookupState(String name, FollowsSyncPacket.Lookup state) {}

    private static final Map<UUID, LookupState> LOOKUPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static int ticks;

    private FollowService() {}

    // ---------------- reads ----------------

    public static AuthorFollows follows(ServerPlayer player) {
        return player.getData(ModDataAttachments.PERK_FOLLOWS.get());
    }

    public static FollowFinds finds(ServerPlayer player) {
        return player.getData(ModDataAttachments.PERK_FOLLOW_FINDS.get());
    }

    public static int rank(ServerPlayer player) {
        return Perks.rank(player, ArchivistPerks.FAV_AUTHOR);
    }

    /** Active follows' tokens that still have something unread; empty without the perk. */
    public static List<String> findableTokens(ServerPlayer player) {
        int rank = rank(player);
        return rank <= 0 ? List.of() : follows(player).findableTokens(rank);
    }

    /** Active follows' tokens; empty without the perk. */
    public static List<String> activeTokens(ServerPlayer player) {
        int rank = rank(player);
        return rank <= 0 ? List.of() : follows(player).activeTokens(rank);
    }

    /** The chance a book or framed photo is from an active followed author; 0 without the perk. */
    public static double share(ServerPlayer player) {
        int rank = rank(player);
        if (rank <= 0) return 0;
        return FavouriteAuthor.share(FavouriteAuthor.activeCount(follows(player).follows().size(), rank));
    }

    /**
     * {@code player} found a community book or photo by {@code token} (blank for an item with none) from
     * the player named {@code name}. Counts it for this life and offers the name to the Follow box.
     */
    public static void recordFound(ServerPlayer player, String token, String name) {
        if (player == null) return;
        if ((token == null || token.isBlank()) && (name == null || name.isBlank())) return;
        if (name != null && name.equalsIgnoreCase(player.getScoreboardName())) return;
        String cleanName = name != null && PLAYER_NAME.matcher(name).matches() ? name : "";
        player.setData(ModDataAttachments.PERK_FOLLOW_FINDS.get(), finds(player).found(token, cleanName));
        if (token != null && follows(player).follows(token)) sync(player);
    }

    // ---------------- requests ----------------

    static void request(ServerPlayer player, FollowRequestPacket.Op op, String value) {
        switch (op) {
            case REFRESH -> refresh(player);
            case DISMISS -> {
                LOOKUPS.remove(player.getUUID());
                sync(player);
            }
            case UNFOLLOW -> unfollow(player, value);
            case FOLLOW_NAME -> {
                if (!PLAYER_NAME.matcher(value).matches()) {
                    lookup(player, value, FollowsSyncPacket.Lookup.NOT_FOUND);
                    return;
                }
                follow(player, FollowsRelay.By.NAME, value, value);
            }
            case FOLLOW_BOOK, FOLLOW_PHOTO -> {
                int id;
                try {
                    id = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return;
                }
                if (id <= 0) return;
                follow(player, op == FollowRequestPacket.Op.FOLLOW_BOOK ? FollowsRelay.By.BOOK : FollowsRelay.By.PHOTO,
                    Integer.toString(id), "");
            }
        }
    }

    private static boolean throttled(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < REQUEST_GAP_MS) return true;
        LAST_REQUEST.put(player.getUUID(), now);
        return false;
    }

    private static void follow(ServerPlayer player, FollowsRelay.By by, String value, String shownName) {
        if (!NetworkConsentMirror.isGranted(player)) {
            lookup(player, shownName, FollowsSyncPacket.Lookup.UNREACHABLE);
            return;
        }
        if (throttled(player)) return;
        lookup(player, shownName, FollowsSyncPacket.Lookup.PENDING);
        MinecraftServer server = player.getServer();
        UUID id = player.getUUID();
        FollowsRelay.add(id, by, value, result -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            if (!result.answered()) {
                lookup(p, shownName, FollowsSyncPacket.Lookup.UNREACHABLE);
            } else if (result.follow().isEmpty()) {
                lookup(p, shownName, FollowsSyncPacket.Lookup.NOT_FOUND);
            } else {
                AuthorFollows.Follow f = result.follow().get();
                p.setData(ModDataAttachments.PERK_FOLLOWS.get(), follows(p).with(f));
                lookup(p, f.name(), FollowsSyncPacket.Lookup.FOUND);
            }
        }));
    }

    private static void unfollow(ServerPlayer player, String token) {
        if (token.isBlank() || !follows(player).follows(token)) return;
        // Off the list at once; the relay catches up (a refresh would bring it back only if the call failed).
        player.setData(ModDataAttachments.PERK_FOLLOWS.get(), follows(player).without(token));
        sync(player);
        if (NetworkConsentMirror.isGranted(player)) FollowsRelay.remove(player.getUUID(), token, ok -> { });
    }

    private static void lookup(ServerPlayer player, String name, FollowsSyncPacket.Lookup state) {
        LOOKUPS.put(player.getUUID(), new LookupState(name == null ? "" : name, state));
        sync(player);
    }

    /** Re-read the list from the relay, then sync. Without consent, sync what is held. */
    static void refresh(ServerPlayer player) {
        sync(player);
        if (!NetworkConsentMirror.isGranted(player)) return;
        MinecraftServer server = player.getServer();
        UUID id = player.getUUID();
        FollowsRelay.list(id, list -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || list.isEmpty()) return;
            p.setData(ModDataAttachments.PERK_FOLLOWS.get(), new AuthorFollows(list.get()));
            sync(p);
        }));
    }

    /** Send {@code player} their follows, finds and last lookup. */
    public static void sync(ServerPlayer player) {
        FollowFinds finds = finds(player);
        List<FollowsSyncPacket.Row> rows = follows(player).follows().stream()
            .map(f -> new FollowsSyncPacket.Row(f.token(), f.name(), f.exhausted(), finds.count(f.token())))
            .toList();
        LookupState l = LOOKUPS.getOrDefault(player.getUUID(), new LookupState("", FollowsSyncPacket.Lookup.NONE));
        DungeonTrainNet.sendTo(player, new FollowsSyncPacket(rows, finds.suggestions(""), l.name(), l.state()));
    }

    // ---------------- events ----------------

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LOOKUPS.remove(event.getEntity().getUUID());
        LAST_REQUEST.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks < REFRESH_TICKS) return;
        ticks = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!follows(player).isEmpty()) refresh(player);
        }
    }
}
