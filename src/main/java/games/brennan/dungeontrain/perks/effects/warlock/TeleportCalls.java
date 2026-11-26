package games.brennan.dungeontrain.perks.effects.warlock;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.event.DtpPlacementService;
import games.brennan.dungeontrain.nap.NapGateData;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import games.brennan.playermob.entity.TargetCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport Player ({@link WarlockPerks#TELEPORT_PLAYER}): the details card's Call a player button
 * lists online players, then the PlayerMobs met this life ({@link MetPlayerMobs}), and calls one.
 *
 * <p>A player gets a chat line with an Accept button ({@link ChatClick}, no command) for
 * {@link #REQUEST_TICKS}; the cooldown starts when they accept. A PlayerMob answers at once: always when
 * it likes the caller (its reaction to them is a greeting), otherwise by its friendliness
 * ({@link WarlockPerks#playerMobAcceptPercent}); a refusal still starts the cooldown. A loaded PlayerMob
 * is moved; one whose chunk isn't loaded, or from an earlier run in another world, is rebuilt from its
 * snapshot, and the copy left behind vanishes when its chunk loads ({@link WarlockCallData}).</p>
 *
 * <p>Arrival is the caller's spot. A player called to someone standing on the train lands on its deck
 * the way {@code /dtp} lands them ({@link DtpPlacementService}), so the Sable sub-level is ready on
 * their client first. The cooldown is a top-level key of the caller's persistent data (one per life);
 * a time from another world's clock (carried through a nap) counts as run out.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TeleportCalls {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String COOLDOWN_KEY = "dungeontrain:teleport_player_ready_at";
    static final int REQUEST_TICKS = 60 * 20;
    public static final String CLICK_ACTION = "warlock-call";
    private static final String KEY = "gui.dungeontrain.warlock.call.";

    /** A call waiting on its target's Accept. */
    private record Pending(UUID caller, long expiresAt) {}

    /** Target player UUID to the call waiting on them; one per target, the newest wins. */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private TeleportCalls() {}

    static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** Ticks until the caller may call again. */
    static long cooldownLeft(ServerPlayer player) {
        int rank = Math.max(1, Perks.rank(player, WarlockPerks.TELEPORT_PLAYER));
        long left = player.getPersistentData().getLong(COOLDOWN_KEY) - now(player.server);
        // further off than a full cooldown can only be another world's clock (a nap): it has run out
        return left <= 0 || left > WarlockPerks.teleportCooldownTicks(rank) ? 0 : left;
    }

    private static void startCooldown(ServerPlayer player) {
        int rank = Math.max(1, Perks.rank(player, WarlockPerks.TELEPORT_PLAYER));
        player.getPersistentData().putLong(COOLDOWN_KEY, now(player.server) + WarlockPerks.teleportCooldownTicks(rank));
    }

    /** Who the caller can call now: online players, then met PlayerMobs. */
    static List<TeleportListPacket.Entry> entries(ServerPlayer caller) {
        List<TeleportListPacket.Entry> out = new ArrayList<>();
        for (ServerPlayer p : caller.server.getPlayerList().getPlayers()) {
            if (p == caller || !p.isAlive() || p.isSpectator()) continue;
            out.add(new TeleportListPacket.Entry(TeleportListPacket.Entry.PLAYER, p.getUUID(), p.getGameProfile().getName()));
        }
        for (MetPlayerMobs.Met m : MetPlayerMobs.list(caller)) {
            out.add(new TeleportListPacket.Entry(TeleportListPacket.Entry.PLAYER_MOB, m.id(), m.name()));
        }
        return out;
    }

    static void sendList(ServerPlayer caller) {
        if (Perks.rank(caller, WarlockPerks.TELEPORT_PLAYER) <= 0) return;
        DungeonTrainNet.sendTo(caller, new TeleportListPacket(entries(caller), (int) cooldownLeft(caller)));
    }

    static void call(ServerPlayer caller, byte kind, UUID id) {
        if (Perks.rank(caller, WarlockPerks.TELEPORT_PLAYER) <= 0 || !caller.isAlive()) return;
        long left = cooldownLeft(caller);
        if (left > 0) {
            caller.displayClientMessage(Component.translatable("gui.dungeontrain.warlock.ready_in", Component.translatable("gui.dungeontrain.perks.stat.unit.seconds", (left + 19) / 20)), true);
        } else if (kind == TeleportListPacket.Entry.PLAYER) {
            callPlayer(caller, id);
        } else {
            callPlayerMob(caller, id);
        }
        sendList(caller);
    }

    private static void callPlayer(ServerPlayer caller, UUID id) {
        ServerPlayer target = caller.server.getPlayerList().getPlayer(id);
        if (target == null || target == caller || !target.isAlive()) {
            caller.displayClientMessage(Component.translatable(KEY + "gone"), true);
            return;
        }
        PENDING.values().removeIf(p -> p.expiresAt() < now(caller.server));
        PENDING.put(target.getUUID(), new Pending(caller.getUUID(), now(caller.server) + REQUEST_TICKS));
        Component accept = Component.translatable(KEY + "accept").withStyle(s -> s
            .withColor(ChatFormatting.GREEN).withUnderlined(true)
            .withClickEvent(ChatClick.event(CLICK_ACTION, "accept", caller.getUUID().toString()))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(KEY + "accept_hover"))));
        target.sendSystemMessage(Component.translatable(KEY + "request", caller.getDisplayName(), accept));
        caller.displayClientMessage(Component.translatable(KEY + "sent", target.getDisplayName()), true);
    }

    /** The target pressed Accept on {@code callerId}'s request. */
    static void accept(ServerPlayer target, UUID callerId) {
        Pending pending = PENDING.get(target.getUUID());
        if (pending == null || !pending.caller().equals(callerId) || pending.expiresAt() < now(target.server)) {
            target.displayClientMessage(Component.translatable(KEY + "expired"), true);
            return;
        }
        PENDING.remove(target.getUUID());
        ServerPlayer caller = target.server.getPlayerList().getPlayer(callerId);
        if (caller == null || !caller.isAlive() || !target.isAlive()) {
            target.displayClientMessage(Component.translatable(KEY + "expired"), true);
            return;
        }
        movePlayer(target, caller);
        startCooldown(caller);
        caller.displayClientMessage(Component.translatable(KEY + "arrived", target.getDisplayName()), true);
        LOGGER.info("[DT-Perks] teleport-player: {} called {}", caller.getScoreboardName(), target.getScoreboardName());
    }

    private static void movePlayer(ServerPlayer target, ServerPlayer caller) {
        target.stopRiding();
        ServerLevel level = caller.serverLevel();
        target.teleportTo(level, caller.getX(), caller.getY(), caller.getZ(), caller.getYRot(), caller.getXRot());
        target.fallDistance = 0f;
        // on the train the deck is a Sable sub-level their client may not have yet: land them as /dtp does
        if (onTrain(caller)) DtpPlacementService.enqueue(target, level, caller.getX());
    }

    /** True when {@code player} stands on a train's carriages, the test boarding progress uses. */
    static boolean onTrain(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD || !NapGateData.onTrainLevel(player)) return false;
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) {
            if (CarriageDeck.isOnTrainFootprint(train, player)) return true;
        }
        return false;
    }

    /**
     * A called PlayerMob is nobody's carriage contents any more: without this, the carriage it spawned
     * in would sweep it back into its snapshot when the train leaves that carriage behind.
     */
    static void stripTrainTags(Entity mob) {
        for (String tag : List.copyOf(mob.getTags())) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) mob.removeTag(tag);
        }
    }

    private static void callPlayerMob(ServerPlayer caller, UUID id) {
        MetPlayerMobs.Met met = MetPlayerMobs.find(caller, id);
        if (met == null) {
            caller.displayClientMessage(Component.translatable(KEY + "gone"), true);
            return;
        }
        // a mob another player called away lives on under a new id: follow it there
        WarlockCallData data = WarlockCallData.get(caller.serverLevel());
        UUID current = data.current(id);
        PlayerMobEntity loaded = findLoaded(caller.server, current);
        PlayerMobEntity mob = loaded != null ? loaded : rebuild(caller, met);
        if (mob == null) {
            MetPlayerMobs.forget(caller, id);
            caller.displayClientMessage(Component.translatable(KEY + "gone"), true);
            return;
        }
        startCooldown(caller);
        int percent = WarlockPerks.playerMobAcceptPercent(likes(mob, caller), mob.friendliness());
        if (caller.getRandom().nextInt(100) >= percent) {
            if (loaded == null) mob.discard();
            caller.displayClientMessage(Component.translatable(KEY + "refused", met.name()), true);
            return;
        }
        if (loaded != null) {
            stripTrainTags(loaded);
            Entity moved = moveMob(loaded, caller);
            if (moved instanceof PlayerMobEntity m) {
                if (!m.getUUID().equals(id)) MetPlayerMobs.rename(caller, id, m);
                else MetPlayerMobs.record(caller, m, true);
            }
        } else {
            stripTrainTags(mob);
            data.markCalledAway(current);
            data.replace(current, mob.getUUID());
            caller.serverLevel().addFreshEntity(mob);
            MetPlayerMobs.rename(caller, id, mob);
        }
        caller.displayClientMessage(Component.translatable(KEY + "arrived", Component.literal(met.name())), true);
        LOGGER.info("[DT-Perks] teleport-player: {} called PlayerMob {} ({})", caller.getScoreboardName(), met.name(),
            loaded != null ? "moved" : "rebuilt");
    }

    /** Whether the mob's reaction to the caller is a greeting: it likes them. */
    static boolean likes(PlayerMobEntity mob, ServerPlayer caller) {
        Reaction r = DispositionResolver.resolve(mob.fightFlight(), mob.friendliness(), mob.feelingToward(caller),
            TargetCategory.PLAYERS, Double.MAX_VALUE);
        return r == Reaction.GREET;
    }

    private static PlayerMobEntity findLoaded(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof PlayerMobEntity mob && mob.isAlive()) return mob;
        }
        return null;
    }

    /** The mob as it was last saved, at the caller, with a new id (the old one stays with the copy left behind). */
    private static PlayerMobEntity rebuild(ServerPlayer caller, MetPlayerMobs.Met met) {
        ServerLevel level = caller.serverLevel();
        Optional<Entity> made;
        try {
            made = EntityType.create(met.snapshot().copy(), level);
        } catch (RuntimeException e) {
            LOGGER.warn("[DT-Perks] teleport-player: could not rebuild PlayerMob {}", met.name(), e);
            return null;
        }
        if (made.isEmpty() || !(made.get() instanceof PlayerMobEntity mob)) return null;
        mob.setUUID(UUID.randomUUID());
        mob.moveTo(caller.getX(), caller.getY(), caller.getZ(), caller.getYRot(), 0f);
        mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        mob.fallDistance = 0f;
        return mob;
    }

    private static Entity moveMob(PlayerMobEntity mob, ServerPlayer caller) {
        mob.stopRiding();
        mob.getNavigation().stop();
        ServerLevel level = caller.serverLevel();
        if (mob.level() == level) {
            mob.teleportTo(caller.getX(), caller.getY(), caller.getZ());
            mob.fallDistance = 0f;
            return mob;
        }
        Entity moved = mob.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(level,
            caller.position(), net.minecraft.world.phys.Vec3.ZERO, caller.getYRot(), 0f,
            net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
        return moved;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.remove(id);
        PENDING.values().removeIf(p -> p.caller().equals(id));
    }
}
