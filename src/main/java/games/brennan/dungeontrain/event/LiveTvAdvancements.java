package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Dark Room's livestream and TV advancements that need watching over time: wearing a Vista TV
 * as a hat ({@code wore_tv}), keeping it on ({@code wore_tv_15_min}), and streaming without a
 * break ({@code livestream_15_min}) or for a lifetime total ({@code livestream_total}). The one-shot ones fire where the action happens —
 * {@code started_livestream} via {@link #onStreamStarted} from {@link LiveFeedEvents},
 * {@code toggled_placed_tv} in {@code mixin/vista/TVBlockMixin}, {@code toggled_held_tv} in
 * {@code net/TvHeldTogglePacket}.
 *
 * <p>Both timers are continuous and in memory only: taking the TV off, the stream ending, a
 * logout or a server stop starts them again from zero. The minutes needed are the advancement
 * JSON's {@code threshold}, read through {@link AdvancementRequirements}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveTvAdvancements {

    /** Fallback minutes for both 15-minute tiers, used when the JSON carries no threshold. */
    private static final int FIFTEEN_MINUTES = 15;
    private static final int TICKS_PER_SECOND = 20;
    private static final int TICKS_PER_MINUTE = 60 * TICKS_PER_SECOND;

    private static final ResourceLocation STREAM_15_ID = darkroom("prime_time");
    private static final ResourceLocation WORN_15_ID = darkroom("couch_potato");
    private static final ResourceLocation STREAM_TOTAL_ID = darkroom("tuned_in");
    /** Fallback lifetime streaming time, in ticks (2 hours), used when the JSON carries no thresholdTicks. */
    private static final long TWO_HOURS_TICKS = 2L * 60 * 60 * TICKS_PER_SECOND;

    /** Server tick each player put a TV on their head, while it stays there. */
    private static final Map<UUID, Long> WORN_SINCE = new HashMap<>();
    /** The streamer being timed and the server tick their stream started; null while nobody is. */
    private static UUID timedStreamer;
    private static long streamSince;

    private LiveTvAdvancements() {}

    private static ResourceLocation darkroom(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/" + name);
    }

    /** A stream has started on this server ({@link LiveFeedEvents}): earn the first tier, start its timer. */
    static void onStreamStarted(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "started_livestream");
        timedStreamer = player.getUUID();
        streamSince = player.server.getTickCount();
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
    }

    private static void checkStream(MinecraftServer server, long now) {
        UUID streamer = LiveStreamers.get(server);
        if (streamer == null || !streamer.equals(timedStreamer)) {
            timedStreamer = null;
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(streamer);
        if (player == null) return;
        if (reached(now, streamSince, STREAM_15_ID)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "livestream_15_min");
        }
        long total = player.getData(ModDataAttachments.LIVESTREAM_TICKS.get()) + TICKS_PER_SECOND;
        player.setData(ModDataAttachments.LIVESTREAM_TICKS.get(), total);
        if (total >= AdvancementRequirements.value(STREAM_TOTAL_ID, TWO_HOURS_TICKS)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "livestream_total");
        }
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
        if (event.getEntity() instanceof ServerPlayer player && TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD))) {
            WORN_SINCE.put(player.getUUID(), (long) player.server.getTickCount());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WORN_SINCE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WORN_SINCE.clear();
        timedStreamer = null;
    }
}
