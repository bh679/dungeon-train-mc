package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.vista.LiveBroadcastLocation;
import games.brennan.dungeontrain.net.LiveStreamEndedPacket;
import games.brennan.dungeontrain.net.LiveLifePacket;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.net.LiveClaimResultPacket;
import games.brennan.dungeontrain.player.LifeKey;
import games.brennan.dungeontrain.player.LiveWatchTally;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.item.LiveEyeId;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Server half of the Live Feed: the broadcast camcorder.
 *
 * <p>Wearing the camcorder is the only way to become the streamer. The server sees the HEAD slot
 * change, remembers the wearer as pending in {@link LiveStreamers} and sends them
 * {@link LiveStreamPacket.Action#START}. Their client claims the channel from the relay — which is
 * where takeover is decided, across worlds: a streamer's first ten minutes are protected (the wearer
 * may ask the audience to vote), and after that the wearer is still asked before taking it. Once the
 * relay gives them the channel, the client says so ({@link LiveClaimResultPacket.Result#LIVE}) and they
 * become the streamer; the previous streamer on this server is told, and their Live Eye becomes a TV.</p>
 *
 * <p>The camcorder stays on while the stream runs and burns away the moment it comes off — taken
 * off, swapped for a helmet, death ({@link #burnsOn(Exit)}). Losing the channel to another streamer
 * ({@link Exit#REPLACED}, {@link Exit#CUT_OFF}), losing a takeover vote or choosing to stay a viewer
 * turns it into a TV instead, so its wearer keeps watching. Only a stream that never started hands it
 * back, and a logout leaves it on so logging back in resumes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveFeedEvents {

    /** Why a stream ended, from the server's point of view. */
    public enum Exit { REMOVED, REPLACED, DIED, LEFT, CUT_OFF, FAILED, DISABLED, FREE_PLAY, MODERATED, BANNED }

    /** How often (server ticks) the streamer's Free Play state is rechecked mid-stream. */
    private static final int FREE_PLAY_CHECK_TICKS = 20;
    /** Client command that reopens the internet consent card ({@code LiveOptionsCommand}). */
    public static final String CONSENT_COMMAND = "/dungeontrain-consent";

    private LiveFeedEvents() {}

    /**
     * Everything burns the camcorder except a failed start (handed back) and a logout (stays on).
     * Internet off on the consent card never gets this far: it is handed back before the stream starts.
     * Putting it on with Livestreaming switched off ({@link Exit#DISABLED}) burns it too: the switch
     * is on the consent card and in Options, and the client says so in chat.
     */
    static boolean burnsOn(Exit exit) {
        return exit != Exit.FAILED && exit != Exit.LEFT && !becomesTv(exit);
    }

    /** Another streamer took the channel: the Live Eye becomes a TV, so its wearer keeps watching. */
    static boolean becomesTv(Exit exit) {
        return exit == Exit.REPLACED || exit == Exit.CUT_OFF;
    }

    /** Every server level learns that the feed id lives at the block-less live location. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) LiveBroadcastLocation.link(level);
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.HEAD) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        boolean wasOn = isCamcorder(event.getFrom());
        boolean isOn = isCamcorder(event.getTo());
        if (isOn && !wasOn) {
            // Read before the stream starts: once it does, this player is the one live.
            boolean fromWatching = TvItems.isTv(event.getFrom()) && ServerLiveStatus.isSomeoneElseLive(player.getServer(), player);
            startStreaming(player, fromWatching);
        } else if (wasOn && !isOn) {
            // Taken off by the player — or just burned/returned by us, in which case the streamer
            // was already cleared and this is a no-op.
            endStream(player, Exit.REMOVED);
        }
    }

    /** A player who logs back in still wearing the camcorder picks the stream up again. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        sendLife(player);
        if (isCamcorder(player.getItemBySlot(EquipmentSlot.HEAD))) startStreaming(player, false);
    }

    /** A respawn is a new life: tell the client its id for the one-life Live Feed boards. */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendLife(player);
    }

    private static void sendLife(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new LiveLifePacket(LifeKey.of(player)));
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) endStream(player, Exit.DIED);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LiveWatchTally.forget(event.getEntity().getUUID());
        // The connection is going away; the packet may not land, but the client stops itself on
        // LoggingOut too. The camcorder stays on, so logging back in resumes.
        if (event.getEntity() instanceof ServerPlayer player) endStream(player, Exit.LEFT);
    }

    /** The streamer's client says its stream ended on its own ({@link LiveStreamEndedPacket}). */
    public static void onClientEnded(ServerPlayer player, LiveStreamEndedPacket.Reason reason) {
        Exit exit = switch (reason) {
            case CUT_OFF -> Exit.CUT_OFF;
            case DISABLED -> Exit.DISABLED;
            case FAILED -> Exit.FAILED;
            case BANNED -> Exit.BANNED;
        };
        endStream(player, exit);
    }

    /**
     * A Free Play run never goes live: the camcorder burns on the way on, and a stream already
     * running ends the moment the run turns Free Play (creative, an op online, a changed config…).
     * Dev builds are exempt so streaming can be tested in creative — the same carve-out as
     * {@code FreePlayBridge#enforceMatch}.
     */
    static boolean streamBlocked(boolean devBuild, boolean freePlay) {
        return !devBuild && freePlay;
    }

    private static boolean streamBlocked(ServerPlayer player) {
        return streamBlocked(DungeonTrain.isDevBuild(), RunIntegrity.isCheated(player));
    }

    /** Once a second, end the stream of a streamer whose run has turned Free Play. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % FREE_PLAY_CHECK_TICKS != 0) return;
        UUID streamer = LiveStreamers.get(server);
        if (streamer == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(streamer);
        if (player != null && streamBlocked(player)) endStream(player, Exit.FREE_PLAY);
    }

    /**
     * {@code player} already holds the stream. Logging in with the camcorder on starts it, and
     * vanilla's first-tick equipment sync (EMPTY → camcorder) then reaches the equip path too —
     * without this the client got two STARTs: two claims, two advancement triggers, two beacons.
     */
    static boolean alreadyStreaming(UUID player, @Nullable UUID current) {
        return player.equals(current);
    }

    private static void startStreaming(ServerPlayer player, boolean fromWatching) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (alreadyStreaming(player.getUUID(), LiveStreamers.get(server))) return;
        if (LiveStreamers.isPending(server, player.getUUID())) return;
        if (streamBlocked(player)) {
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.live.free_play").withStyle(ChatFormatting.GRAY));
            burn(player, Exit.FREE_PLAY);
            return;
        }
        // Banned from the Live Eye by the text-filter ladder (LiveModeration): it burns on the way on.
        if (LiveModeration.isBanned(player.getUUID())) {
            burn(player, Exit.BANNED); // its line says how long, and lists the lines to appeal
            // An approved appeal lifts the ban on the relay; ask again so the next try reflects it.
            LiveModeration.refresh(player);
            return;
        }
        // Internet switched off on the consent card: the Live Eye won't go on. It goes back in the
        // inventory and chat links to the card; once they accept, putting it on again streams.
        UUID id = player.getUUID();
        if (needsInternet(NetworkConsentMirror.isKnown(id), NetworkConsentMirror.isGranted(player))) {
            handBack(player);
            player.sendSystemMessage(needsInternetLine());
            return;
        }
        // Pending until the relay gives them the channel (onTakeoverResult LIVE): someone else may hold it.
        LiveStreamers.addPending(server, player.getUUID(), fromWatching);
        String eyeId = LiveEyeId.ensure(player.getItemBySlot(EquipmentSlot.HEAD));
        PacketDistributor.sendToPlayer(player, LiveStreamPacket.start(eyeId));
    }

    /**
     * The client heard back from the relay about its claim ({@link LiveClaimResultPacket}). Only a pending
     * wearer is listened to: anyone else's packet is stale or forged.
     */
    public static void onTakeoverResult(ServerPlayer player, LiveClaimResultPacket.Result result) {
        MinecraftServer server = player.getServer();
        if (server == null || !isCamcorder(player.getItemBySlot(EquipmentSlot.HEAD))) return;
        Boolean fromWatching = LiveStreamers.takePending(server, player.getUUID());
        if (fromWatching == null) return;
        switch (result) {
            case LIVE -> goLive(server, player, fromWatching);
            case REJECTED -> toTv(player, "chat.dungeontrain.live.takeover_rejected_tv");
            case STAYED_VIEWER -> toTv(player, "chat.dungeontrain.live.stayed_viewer_tv");
        }
    }

    /** {@code player} holds the channel now: they are this server's streamer, and the previous one's Live Eye becomes a TV. */
    private static void goLive(MinecraftServer server, ServerPlayer player, boolean fromWatching) {
        UUID previous = LiveStreamers.setStreamer(server, player.getUUID());
        if (previous != null && !previous.equals(player.getUUID())) {
            ServerPlayer old = server.getPlayerList().getPlayer(previous);
            if (old != null) {
                toTv(old, burnLineKey(Exit.REPLACED));
                PacketDistributor.sendToPlayer(old, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_REPLACED,
                    player.getGameProfile().getName()));
            }
        }
        LiveTvAdvancements.onStreamStarted(player);
        if (fromWatching) LiveTvAdvancements.onTookOverFromTv(player);
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.5f, 1.6f);
        // No chat line here: the client says "You are now live" once the relay has a viewer count.
    }

    /**
     * The player's client has told us internet is off. Unknown (login sync not landed yet) lets the
     * stream start: the client checks its own consent and hands the Live Eye back the same way.
     */
    static boolean needsInternet(boolean known, boolean granted) {
        return known && !granted;
    }

    /** "The Live Eye needs the internet — [turn it on]", the link opening the consent card. */
    public static Component needsInternetLine() {
        return Component.translatable("chat.dungeontrain.live.needs_internet",
                Component.translatable("chat.dungeontrain.live.needs_internet.link").withStyle(style -> style
                    .withUnderlined(true)
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, CONSENT_COMMAND))))
            .withStyle(ChatFormatting.GRAY);
    }

    /** Players whose takeover ticket is being checked with the relay, so one cannot be sent twice at once. */
    private static final java.util.Set<UUID> TAKEOVER_CHECKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A viewer won a dead streamer's takeover (relay ticket, {@code net/LiveTakeoverPacket}): once the
     * relay vouches for the ticket, the TV on their head becomes a Live Eye — consumed, the way a
     * helmet swap burns a camcorder — and {@link #onEquipmentChange} starts their stream from there.
     * Nothing happens without a worn TV, so a modified client cannot mint Live Eyes.
     */
    public static void onTakeoverWon(ServerPlayer player, String ticket) {
        MinecraftServer server = player.getServer();
        if (server == null || !TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD))) return;
        UUID uuid = player.getUUID();
        if (!TAKEOVER_CHECKS.add(uuid)) return;
        games.brennan.dungeontrain.net.relay.LiveFeedClient.verifyTakeover(ticket, uuid).whenComplete((valid, err) ->
            server.execute(() -> {
                TAKEOVER_CHECKS.remove(uuid);
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null || !Boolean.TRUE.equals(valid)) return;
                if (!p.isAlive() || !TvItems.isTv(p.getItemBySlot(EquipmentSlot.HEAD))) return;
                p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.LIVE_HEADPIECE.get()));
            }));
    }

    /**
     * The text-filter ladder struck {@code player} out: the Live Eye burns and the stream ends
     * ({@link Exit#MODERATED}), or they are banned from it outright ({@link Exit#BANNED}). Either way
     * the camcorder goes, even when no stream is running — a banned player may not keep one.
     */
    public static void endForModeration(ServerPlayer player, boolean banned) {
        Exit exit = banned ? Exit.BANNED : Exit.MODERATED;
        if (!endStream(player, exit)) burn(player, exit);
    }

    /** Ends {@code player}'s stream if they hold it: clear the streamer first so the slot change we cause is ignored. True when they did. */
    private static boolean endStream(ServerPlayer player, Exit exit) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        // A pending wearer (still claiming, or watching while deciding on a takeover) ends the same way:
        // taking the Live Eye off burns it.
        boolean held = LiveStreamers.clearIf(server, player.getUUID());
        if (!held && LiveStreamers.takePending(server, player.getUUID()) == null) return false;
        if (becomesTv(exit)) {
            toTv(player, burnLineKey(exit));
        } else if (burnsOn(exit)) {
            burn(player, exit);
        } else if (exit == Exit.FAILED) {
            handBack(player);
        }
        switch (exit) {
            case REMOVED -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_REMOVED, ""));
            case DIED -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_DIED, ""));
            case LEFT -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_LEFT, ""));
            case FREE_PLAY -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_FREE_PLAY, ""));
            case MODERATED, BANNED -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_MODERATED, ""));
            default -> { /* REPLACED is sent by the new streamer's start; CUT_OFF and FAILED came from the client */ }
        }
        return true;
    }

    /**
     * The camcorder flares and is gone: flame, smoke, a hiss. It is taken from the head slot, or —
     * when the player has just pulled it off — from the cursor or the inventory slot it landed in.
     * The chat line says why ({@link #burnLineKey}); the streamer's client raises the "Livestream
     * ended" pop-up itself, so it shows on every end — save &amp; quit included.
     */
    private static void burn(ServerPlayer player, Exit why) {
        if (isCamcorder(player.getItemBySlot(EquipmentSlot.HEAD))) {
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        } else if (isCamcorder(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        } else if (player.getInventory().clearOrCountMatchingItems(LiveFeedEvents::isCamcorder, 1, player.inventoryMenu.getCraftSlots()) == 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getEyeY() + 0.3, player.getZ(), 24, 0.3, 0.2, 0.3, 0.02);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getEyeY() + 0.4, player.getZ(), 8, 0.2, 0.1, 0.2, 0.01);
        level.playSound(null, player.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 0.9f);
        player.sendSystemMessage(why == Exit.BANNED ? LiveModeration.bannedBurnLine(player) : Component.translatable(burnLineKey(why)));
    }

    /**
     * The Live Eye becomes a switched-on Vista TV where it is — the head slot, or the cursor or inventory
     * slot it was just pulled to — so its wearer keeps watching. A puff of smoke, no flame; {@code lineKey}
     * says why. Without Vista's TV it burns instead. Callers clear the player from {@link LiveStreamers}
     * first, so the slot change this causes is ignored.
     */
    private static void toTv(ServerPlayer player, String lineKey) {
        ItemStack tv = LiveEyeLootResolver.tv();
        if (tv.isEmpty()) {
            burn(player, Exit.REPLACED);
            return;
        }
        tv = TvItems.withOn(tv, true);
        if (isCamcorder(player.getItemBySlot(EquipmentSlot.HEAD))) {
            player.setItemSlot(EquipmentSlot.HEAD, tv);
        } else if (isCamcorder(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(tv);
        } else {
            int slot = findCamcorder(player);
            if (slot < 0) return;
            player.getInventory().setItem(slot, tv);
        }
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getEyeY() + 0.4, player.getZ(), 8, 0.2, 0.1, 0.2, 0.01);
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5f, 1.4f);
        player.sendSystemMessage(Component.translatable(lineKey));
    }

    private static int findCamcorder(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isCamcorder(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** Taken off, handed to the next streamer, or anything else (death, Free Play, Livestreaming off). */
    static String burnLineKey(Exit why) {
        return switch (why) {
            case REMOVED -> "chat.dungeontrain.live.burns_removed";
            case REPLACED, CUT_OFF -> "chat.dungeontrain.live.becomes_tv_next_streamer";
            case MODERATED -> "chat.dungeontrain.live.burns_moderated";
            case BANNED -> "chat.dungeontrain.live.burns_banned";
            default -> "chat.dungeontrain.live.headpiece_burns";
        };
    }

    /** Nothing to broadcast: the camcorder comes off and goes back in the inventory. */
    private static void handBack(ServerPlayer player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!isCamcorder(head)) return;
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.getInventory().placeItemBackInInventory(head);
    }

    /**
     * A real Live Eye. A loot-rolled one still awaiting its finder check is not — it may yet become a
     * TV ({@link LiveEyeLootResolver}); the real one it resolves into starts the stream instead.
     */
    private static boolean isCamcorder(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.LIVE_HEADPIECE.get())
            && !LiveHeadpiecePendingTag.isPending(stack);
    }
}
