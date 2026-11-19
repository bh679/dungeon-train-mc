package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.advancement.GlobalLivestreamStats;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Dark Room's livestream and TV advancements that need watching over time: wearing a Vista TV
 * as a hat ({@code wore_tv}), keeping it on ({@code wore_tv_15_min}), and streaming without a
 * break ({@code livestream_15_min}) or for a lifetime total ({@code livestream_total}). The one-shot ones fire where the action happens —
 * {@code started_livestream} via {@link #onStreamStarted} from {@link LiveFeedEvents},
 * {@code toggled_placed_tv} in {@code mixin/vista/TVBlockMixin}, {@code toggled_held_tv} and
 * {@code held_tv_while_live} via {@link #onHeldTvToggled} from {@code net/TvHeldTogglePacket}.
 *
 * <p>Both timers are continuous and in memory only: taking the TV off, the stream ending, a
 * logout or a server stop starts them again from zero. The minutes needed are the advancement
 * JSON's {@code threshold}, read through {@link AdvancementRequirements}.</p>
 *
 * <p>The lifetime total is kept outside the world ({@link GlobalLivestreamStats}), since a run gets a
 * new world. What an older save still holds moves across at login, and the relay's own all-time figure
 * raises it when the login rank fetch lands ({@link #onRelayStreamTotal}), so stream time from before
 * the total left the world still counts. That earn waits for the intro cinematic to finish.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveTvAdvancements {

    /** Fallback minutes for both 15-minute tiers, used when the JSON carries no threshold. */
    private static final int FIFTEEN_MINUTES = 15;
    private static final int TICKS_PER_SECOND = 20;
    private static final int TICKS_PER_MINUTE = 60 * TICKS_PER_SECOND;

    private static final ResourceLocation STREAM_15_ID = darkroom("prime_time");
    private static final ResourceLocation WORN_15_ID = darkroom("couch_potato");
    private static final ResourceLocation STREAM_TOTAL_ID = darkroom("for_the_fans");
    /** Fallback lifetime streaming time, in ticks (2 hours), used when the JSON carries no thresholdTicks. */
    private static final long TWO_HOURS_TICKS = 2L * 60 * 60 * TICKS_PER_SECOND;
    /** How often a running stream's lifetime total reaches the disk: a crash loses at most this much. */
    private static final long TOTAL_SAVE_PERIOD_TICKS = 5L * TICKS_PER_MINUTE;

    /** Server tick each player put a TV on their head, while it stays there. */
    private static final Map<UUID, Long> WORN_SINCE = new HashMap<>();
    /** Server tick each streamer's current stream started, while it runs (two with Two Streams on). */
    private static final Map<UUID, Long> STREAM_SINCE = new HashMap<>();
    /**
     * Players whose lifetime total was just raised outside a stream. Judged on the next second's tick, so
     * a login's check always runs after that login's advancement replay: granting before it would earn the
     * advancement afresh, toast and all, in every new world.
     */
    private static final Set<UUID> TOTAL_TO_CHECK = new HashSet<>();

    private LiveTvAdvancements() {}

    private static ResourceLocation darkroom(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/" + name);
    }

    /** A stream has started on this server ({@link LiveFeedEvents}): earn the first tier, start its timer. */
    static void onStreamStarted(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "started_livestream");
        STREAM_SINCE.put(player.getUUID(), (long) player.server.getTickCount());
    }

    /** Swapped the TV on their head for a Live Eye while someone else was live, and went live ({@link LiveFeedEvents}). */
    static void onTookOverFromTv(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "tv_to_live_eye");
    }

    /**
     * Switched a TV in their hand on or off ({@code net/TvHeldTogglePacket}). Switching it on while
     * they are the one streaming — the Live Eye on their head — puts their own stream in their hands.
     */
    public static void onHeldTvToggled(ServerPlayer player, boolean on) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "toggled_held_tv");
        UUID self = player.getUUID();
        if (watchingOwnStream(on, LiveStreamers.isStreamer(player.server, self) ? self : null, self)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "held_tv_while_live");
        }
    }

    /**
     * A held TV switched on by this server's streamer. Only the streamer counts: taking the Live Eye
     * off ends the stream ({@link LiveFeedEvents}). Pure, for the tests.
     */
    static boolean watchingOwnStream(boolean switchedOn, @Nullable UUID streamer, UUID player) {
        return switchedOn && player.equals(streamer);
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.HEAD) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        boolean wasTv = TvItems.isTv(event.getFrom());
        boolean isTv = TvItems.isTv(event.getTo());
        if (isTv && !wasTv) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "wore_tv");
            WORN_SINCE.put(player.getUUID(), (long) player.server.getTickCount());
        } else if (!isTv) {
            WORN_SINCE.remove(player.getUUID());
        }
    }

    /** Once a second: grant the 15-minute tiers to whoever has kept at it long enough. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        if (now % TICKS_PER_SECOND != 0) return;
        checkStream(server, now);
        checkWorn(server, now);
        checkRaisedTotals(server);
    }

    private static void checkStream(MinecraftServer server, long now) {
        if (STREAM_SINCE.isEmpty()) return;
        for (UUID id : List.copyOf(STREAM_SINCE.keySet())) {
            if (!LiveStreamers.isStreamer(server, id)) {
                STREAM_SINCE.remove(id);
                GlobalLivestreamStats.saveAsync(id);
            }
        }
        for (Map.Entry<UUID, Long> e : List.copyOf(STREAM_SINCE.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null) tickStream(player, e.getValue(), now);
        }
    }

    private static void tickStream(ServerPlayer player, long streamSince, long now) {
        if (reached(now, streamSince, STREAM_15_ID)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "livestream_15_min");
        }
        // This life's "Longest Stream" figure for Faulthurst's stat book.
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addLiveStreamSeconds(1);
        long total = GlobalLivestreamStats.addStreamTicks(player.getUUID(), TICKS_PER_SECOND);
        if (total % TOTAL_SAVE_PERIOD_TICKS < TICKS_PER_SECOND) GlobalLivestreamStats.saveAsync(player.getUUID());
        fireIfTotalReached(player, total);
    }

    private static boolean totalReached(long total) {
        return total >= AdvancementRequirements.value(STREAM_TOTAL_ID, TWO_HOURS_TICKS);
    }

    /** Earn the lifetime tier if {@code total} clears the bar, but never over the intro: a stream's next second retries. */
    private static void fireIfTotalReached(ServerPlayer player, long total) {
        if (totalReached(total) && !CinematicIntroService.isIntroPendingOrActive(player)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "livestream_total");
        }
    }

    /**
     * Judge the totals raised outside a stream. A player stays listed while the bar is cleared but the earn can't
     * land yet: the intro is still on, or they are idle, as a player sitting through the intro with the mouse still
     * will be, and the AFK gate would refuse it with nothing to ask again until their next login.
     */
    private static void checkRaisedTotals(MinecraftServer server) {
        if (TOTAL_TO_CHECK.isEmpty()) return;
        for (UUID id : List.copyOf(TOTAL_TO_CHECK)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || RunIntegrity.isCheated(player) || !totalReached(GlobalLivestreamStats.streamTicks(id))) {
                TOTAL_TO_CHECK.remove(id);
            } else if (!CinematicIntroService.isIntroPendingOrActive(player) && !PlayerActivityTracker.isAfk(player)) {
                fireIfTotalReached(player, GlobalLivestreamStats.streamTicks(id));
                TOTAL_TO_CHECK.remove(id);
            }
        }
    }

    /**
     * The relay's all-time seconds on air for {@code player}, from the rank fetch ({@code LeaderboardPool}),
     * on whichever thread that landed. Raises their total and, if that clears the bar, earns the advancement
     * like any other earn. A Free Play run takes the time but earns nothing, as with awards.
     */
    public static void onRelayStreamTotal(UUID player, long seconds) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            if (server.getPlayerList().getPlayer(player) == null) return;
            GlobalLivestreamStats.creditRelay(player, seconds);
            TOTAL_TO_CHECK.add(player);
        });
    }

    /**
     * Move what this world's save still holds from before the total left the world, zeroing it so it can
     * never count twice. A Free Play run's stays where it is, out of the lifetime record.
     */
    private static void absorbWorldTotal(ServerPlayer player) {
        if (RunIntegrity.isCheated(player)) return;
        UUID id = player.getUUID();
        long world = player.getData(ModDataAttachments.LIVESTREAM_TICKS.get());
        if (world > 0L) {
            GlobalLivestreamStats.addStreamTicks(id, world);
            player.setData(ModDataAttachments.LIVESTREAM_TICKS.get(), 0L);
            GlobalLivestreamStats.saveAsync(id);
        }
        TOTAL_TO_CHECK.add(id);
    }

    private static void checkWorn(MinecraftServer server, long now) {
        if (WORN_SINCE.isEmpty()) return;
        for (Map.Entry<UUID, Long> worn : Map.copyOf(WORN_SINCE).entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(worn.getKey());
            if (player == null || !TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD))) {
                WORN_SINCE.remove(worn.getKey());
            } else if (reached(now, worn.getValue(), WORN_15_ID)) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "wore_tv_15_min");
            }
        }
    }

    private static boolean reached(long now, long since, ResourceLocation id) {
        return elapsedMinutes(now, since) >= AdvancementRequirements.intValue(id, FIFTEEN_MINUTES);
    }

    /** Whole minutes between two server ticks. Pure, for the tests. */
    static long elapsedMinutes(long nowTick, long sinceTick) {
        return Math.max(0L, nowTick - sinceTick) / TICKS_PER_MINUTE;
    }

    /** A player logging in already wearing a TV starts the clock again. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD))) {
            WORN_SINCE.put(player.getUUID(), (long) player.server.getTickCount());
        }
        absorbWorldTotal(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WORN_SINCE.remove(event.getEntity().getUUID());
        TOTAL_TO_CHECK.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WORN_SINCE.clear();
        STREAM_SINCE.clear();
        TOTAL_TO_CHECK.clear();
    }
}
