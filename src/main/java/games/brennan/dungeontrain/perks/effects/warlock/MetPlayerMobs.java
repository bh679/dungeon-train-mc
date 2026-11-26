package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerksGate;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The PlayerMobs a player has met this life, for Teleport Player's list. Met means within
 * {@link #MEET_RANGE} blocks (which covers trading and gifting), or hit by the player from anywhere.
 *
 * <p>Kept newest first under {@link #KEY}, a top-level key of the player's persistent data: it resets
 * each life and travels with the player through a nap, so a PlayerMob from an earlier run of the same
 * life, in another world, stays callable. Each entry holds a snapshot of the mob as it saves (name,
 * skin, feelings, gear), refreshed at most every {@link #SNAPSHOT_EVERY} ticks while the player is
 * near, so a mob whose chunk is not loaded can be rebuilt from it. The list keeps its newest
 * {@link #KEEP}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MetPlayerMobs {

    static final String KEY = "dungeontrain:warlock_met";
    static final double MEET_RANGE = 16.0;
    static final int KEEP = 32;
    static final int SCAN_EVERY = 40;
    static final int SNAPSHOT_EVERY = 200;

    /** One met PlayerMob: its id, name, and the snapshot it was last saved as. */
    record Met(UUID id, String name, CompoundTag snapshot, long snapAt) {
        CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("name", name);
            t.put("snap", snapshot);
            t.putLong("snapAt", snapAt);
            return t;
        }

        static Met of(CompoundTag t) {
            return new Met(t.getUUID("id"), t.getString("name"), t.getCompound("snap"), t.getLong("snapAt"));
        }
    }

    private MetPlayerMobs() {}

    /** The player's met PlayerMobs, newest first, without the ones known dead. */
    static List<Met> list(ServerPlayer player) {
        WarlockCallData data = WarlockCallData.get(player.serverLevel());
        List<Met> out = new ArrayList<>();
        for (Tag t : player.getPersistentData().getList(KEY, Tag.TAG_COMPOUND)) {
            Met m = Met.of((CompoundTag) t);
            if (!data.isDead(m.id())) out.add(m);
        }
        return out;
    }

    static Met find(ServerPlayer player, UUID id) {
        for (Met m : list(player)) if (m.id().equals(id)) return m;
        return null;
    }

    /** Records {@code mob} as met just now, at the front; refreshes its snapshot when due or forced. */
    static void record(ServerPlayer player, PlayerMobEntity mob, boolean forceSnapshot) {
        long now = player.server.overworld().getGameTime();
        List<Met> list = new ArrayList<>(rawList(player));
        Met old = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(mob.getUUID())) {
                old = list.remove(i);
                break;
            }
        }
        boolean fresh = old == null || forceSnapshot || now - old.snapAt() >= SNAPSHOT_EVERY || now < old.snapAt();
        Met next = fresh ? new Met(mob.getUUID(), mob.getName().getString(), snapshot(mob), now)
            : new Met(old.id(), mob.getName().getString(), old.snapshot(), old.snapAt());
        list.add(0, next);
        while (list.size() > KEEP) list.remove(list.size() - 1);
        write(player, list);
    }

    /** Swaps an entry's id (a mob rebuilt from its snapshot has a new one). */
    static void rename(ServerPlayer player, UUID from, PlayerMobEntity to) {
        List<Met> list = new ArrayList<>(rawList(player));
        list.removeIf(m -> m.id().equals(from));
        write(player, list);
        record(player, to, true);
    }

    static void forget(ServerPlayer player, UUID id) {
        List<Met> list = new ArrayList<>(rawList(player));
        if (list.removeIf(m -> m.id().equals(id))) write(player, list);
    }

    private static List<Met> rawList(ServerPlayer player) {
        List<Met> out = new ArrayList<>();
        for (Tag t : player.getPersistentData().getList(KEY, Tag.TAG_COMPOUND)) out.add(Met.of((CompoundTag) t));
        return out;
    }

    private static void write(ServerPlayer player, List<Met> list) {
        ListTag tag = new ListTag();
        for (Met m : list) tag.add(m.toTag());
        player.getPersistentData().put(KEY, tag);
    }

    static CompoundTag snapshot(PlayerMobEntity mob) {
        CompoundTag tag = new CompoundTag();
        mob.saveWithoutId(tag);
        tag.putString("id", EntityType.getKey(mob.getType()).toString());
        return tag;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % SCAN_EVERY != 0) return;
        if (!player.isAlive() || player.isSpectator() || !PerksGate.isOn(player)) return;
        for (PlayerMobEntity mob : player.serverLevel().getEntitiesOfClass(PlayerMobEntity.class,
                player.getBoundingBox().inflate(MEET_RANGE), m -> m.isAlive() && m.distanceToSqr(player) <= MEET_RANGE * MEET_RANGE)) {
            record(player, mob, false);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof PlayerMobEntity mob) || mob.level().isClientSide) return;
        if (event.getSource().getEntity() instanceof ServerPlayer player && PerksGate.isOn(player)) record(player, mob, false);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PlayerMobEntity mob) || !(mob.level() instanceof ServerLevel level)) return;
        WarlockCallData.get(level).markDead(mob.getUUID());
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) forget(p, mob.getUUID());
    }

    /** The copy of a called-away mob left in its chunk never comes back. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof PlayerMobEntity mob) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (WarlockCallData.get(level).takeCalledAway(mob.getUUID())) event.setCanceled(true);
    }
}
