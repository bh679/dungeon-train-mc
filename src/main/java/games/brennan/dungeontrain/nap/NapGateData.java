package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each woken player's nap gate ({@link PlayerRunState#setNapGate}) is kept in this world, so it
 * survives a relog: the run-state codec is full. Written on wake, read back into the run state on
 * login, dropped on a death. Stored at {@code <world>/data/dungeontrain_nap_gates.dat}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapGateData extends SavedData {

    public static final String NAME = "dungeontrain_nap_gates";
    private static final String TAG_X = "x";
    private static final String TAG_DIR = "dir";

    private record Gate(double x, int dir) {}

    private final Map<UUID, Gate> gates = new HashMap<>();

    private NapGateData() {}

    public static NapGateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(NapGateData::new, (tag, registries) -> load(tag)), NAME);
    }

    private static NapGateData load(CompoundTag tag) {
        NapGateData data = new NapGateData();
        for (String key : tag.getAllKeys()) {
            try {
                CompoundTag g = tag.getCompound(key);
                int dir = g.getInt(TAG_DIR);
                if (dir != 0) data.gates.put(UUID.fromString(key), new Gate(g.getDouble(TAG_X), Integer.signum(dir)));
            } catch (IllegalArgumentException ignored) {
                // not a player id: skip the entry
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        gates.forEach((id, g) -> {
            CompoundTag t = new CompoundTag();
            t.putDouble(TAG_X, g.x());
            t.putInt(TAG_DIR, g.dir());
            tag.put(id.toString(), t);
        });
        return tag;
    }

    /** Record {@code run}'s gate for {@code player} (or forget it when the run has none). */
    public static void remember(ServerPlayer player, PlayerRunState run) {
        NapGateData data = get(player.server);
        if (run.napGated()) data.gates.put(player.getUUID(), new Gate(run.napGateX(), run.napGateDir()));
        else data.gates.remove(player.getUUID());
        data.setDirty();
    }

    /** After a relog: give the run state back the gate this world holds for the player. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Gate gate = get(player.server).gates.get(player.getUUID());
        if (gate == null) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (!run.napGated()) run.setNapGate(gate.x(), gate.dir());
    }

    /** A death ends the life, and the gate with it. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) return;
        NapGateData data = get(player.server);
        if (data.gates.remove(player.getUUID()) != null) data.setDirty();
    }

    /** -1 when {@code player} is behind their nap point (counters shrink), else +1. */
    public static int step(ServerPlayer player) {
        return player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).counterStep(player.getX());
    }
}
