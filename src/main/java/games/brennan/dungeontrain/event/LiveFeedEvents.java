package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.vista.LiveBroadcastLocation;
import games.brennan.dungeontrain.net.LiveStreamEndedPacket;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
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

import java.util.UUID;

/**
 * Server half of the Live Feed: the broadcast camcorder.
 *
 * <p>Wearing the camcorder is the only way to become the streamer. The server sees the HEAD slot
 * change, remembers the wearer in {@link LiveStreamers}, burns the camcorder off any previous
 * streamer on this server, and sends the wearer {@link LiveStreamPacket.Action#START}. The wearer's
 * client then claims the channel from the relay — which is where cross-world takeover is decided;
 * two single-player worlds each have a "streamer", and the relay's last claim wins.</p>
 *
 * <p>The camcorder stays on while the stream runs and burns away the moment it comes off — taken
 * off, swapped for a helmet, replaced by another streamer, death or a relay cut-off
 * ({@link #burnsOn(Exit)}). Only a stream that never started hands it back, and a logout leaves it
 * on so logging back in resumes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveFeedEvents {

    /** Why a stream ended, from the server's point of view. */
    public enum Exit { REMOVED, REPLACED, DIED, LEFT, CUT_OFF, FAILED, DISABLED, FREE_PLAY }

    /** How often (server ticks) the streamer's Free Play state is rechecked mid-stream. */
    private static final int FREE_PLAY_CHECK_TICKS = 20;

    private LiveFeedEvents() {}

    /**
     * Everything burns the camcorder except a failed start (handed back) and a logout (stays on).
     * Putting it on with Livestreaming switched off ({@link Exit#DISABLED}) burns it too: the switch
     * is on the consent card and in Options, and the client says so in chat.
     */
    static boolean burnsOn(Exit exit) {
        return exit != Exit.FAILED && exit != Exit.LEFT;
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
            startStreaming(player);
        } else if (wasOn && !isOn) {
            // Taken off by the player — or just burned/returned by us, in which case the streamer
            // was already cleared and this is a no-op.
            endStream(player, Exit.REMOVED);
        }
    }

    /** A player who logs back in still wearing the camcorder picks the stream up again. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isCamcorder(player.getItemBySlot(EquipmentSlot.HEAD))) {
            startStreaming(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) endStream(player, Exit.DIED);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
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

    private static void startStreaming(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (streamBlocked(player)) {
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.live.free_play").withStyle(ChatFormatting.GRAY));
            burn(player);
            return;
        }
        UUID previous = LiveStreamers.setStreamer(server, player.getUUID());
        if (previous != null && !previous.equals(player.getUUID())) {
            ServerPlayer old = server.getPlayerList().getPlayer(previous);
            if (old != null) {
                burn(old);
                PacketDistributor.sendToPlayer(old, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_REPLACED,
                    player.getGameProfile().getName()));
            }
        }
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.5f, 1.6f);
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.live.headpiece_on"));
        PacketDistributor.sendToPlayer(player, LiveStreamPacket.start());
    }

    /** Ends {@code player}'s stream if they hold it: clear the streamer first so the slot change we cause is ignored. */
    private static void endStream(ServerPlayer player, Exit exit) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!LiveStreamers.clearIf(server, player.getUUID())) return;
        if (burnsOn(exit)) {
            burn(player);
        } else if (exit == Exit.FAILED) {
            handBack(player);
        }
        switch (exit) {
            case REMOVED -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_REMOVED, ""));
            case DIED -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_DIED, ""));
            case LEFT -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_LEFT, ""));
            case FREE_PLAY -> PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_FREE_PLAY, ""));
            default -> { /* REPLACED is sent by the new streamer's start; CUT_OFF and FAILED came from the client */ }
        }
    }

    /**
     * The camcorder flares and is gone: flame, smoke, a hiss. It is taken from the head slot, or —
     * when the player has just pulled it off — from the cursor or the inventory slot it landed in.
     */
    private static void burn(ServerPlayer player) {
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
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.live.headpiece_burns"));
    }

    /** Nothing to broadcast: the camcorder comes off and goes back in the inventory. */
    private static void handBack(ServerPlayer player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!isCamcorder(head)) return;
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.getInventory().placeItemBackInInventory(head);
    }

    private static boolean isCamcorder(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.LIVE_HEADPIECE.get());
    }
}
