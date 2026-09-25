package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SprintFlyBoostPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Creative sprint-fly boost: a multiplier on the horizontal speed of sprint-flying only. Normal
 * flight and the up/down rate stay vanilla — see {@code mixin/PlayerSprintFlyMixin}, which applies
 * it where vanilla computes sprint-fly speed ({@code Player.getFlyingSpeed}).
 *
 * <p>Set with {@code /dt flyspeed <multiplier>}. The server keeps the chosen value in the player's
 * persisted NBT (survives relog and death); unset means {@link #DEV_DEFAULT} in a dev build and
 * vanilla (1×) in production. Player movement is simulated client-side, so the effective value is
 * pushed to the client ({@link SprintFlyBoostPacket}) on login and whenever it changes; the client
 * keeps it for the session, across respawns and dimension changes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SprintFlyBoost {

    public static final float DEV_DEFAULT = 5f;
    public static final float VANILLA = 1f;
    private static final String KEY = "dungeontrain_sprint_fly_multiplier";

    /** The multiplier the local player flies with; written only by {@link SprintFlyBoostPacket}. */
    private static volatile float clientMultiplier = VANILLA;

    private SprintFlyBoost() {}

    /** Effective multiplier: the stored value, else the build's default. */
    static float resolve(Float stored, boolean production) {
        if (stored != null) return stored;
        return production ? VANILLA : DEV_DEFAULT;
    }

    public static float effective(ServerPlayer player) {
        CompoundTag tag = persisted(player);
        return resolve(tag.contains(KEY) ? tag.getFloat(KEY) : null, FMLEnvironment.production);
    }

    public static void set(ServerPlayer player, float multiplier) {
        CompoundTag root = player.getPersistentData();
        CompoundTag tag = persisted(player);
        tag.putFloat(KEY, multiplier);
        root.put(Player.PERSISTED_NBT_TAG, tag);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new SprintFlyBoostPacket(effective(player)));
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    public static float clientMultiplier() {
        return clientMultiplier;
    }

    public static void setClientMultiplier(float multiplier) {
        clientMultiplier = multiplier;
    }

    private static CompoundTag persisted(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    }
}
