package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Final Moments": a creature that was in a player's photo dies within a second of the shot. Each
 * shot leaves its creatures here for {@link #WINDOW_TICKS}; a death inside that window credits the
 * photographer, and the advancement keeps that photo ({@link AdvancementPhotoCapture}) even though
 * it is earned after the shot. Server thread only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PhotoFinalMoments {

    /** One second. */
    static final long WINDOW_TICKS = 20;

    private record Shot(UUID photographer, Frame frame, long gameTime) {}

    /** Creature UUID → the latest shot it was in. */
    private static final Map<UUID, Shot> RECENT = new HashMap<>();

    private PhotoFinalMoments() {}

    /** True when a death at {@code deathTime} is inside the window of a shot at {@code shotTime}. */
    static boolean inWindow(long shotTime, long deathTime) {
        return deathTime >= shotTime && deathTime - shotTime <= WINDOW_TICKS;
    }

    static void remember(ServerPlayer photographer, Frame frame, List<LivingEntity> inFrame) {
        long now = photographer.serverLevel().getGameTime();
        prune(now);
        for (LivingEntity e : inFrame) {
            if (e instanceof Player) continue;
            RECENT.put(e.getUUID(), new Shot(photographer.getUUID(), frame, now));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (RECENT.isEmpty() || dead.level().isClientSide() || dead.getServer() == null) return;
        Shot shot = RECENT.remove(dead.getUUID());
        if (shot == null || !inWindow(shot.gameTime(), dead.level().getGameTime())) return;
        ServerPlayer photographer = dead.getServer().getPlayerList().getPlayer(shot.photographer());
        if (photographer == null) return;
        AdvancementPhotoCapture.during(shot.frame(), () ->
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(photographer, EnchiridionAdvancements.PHOTO_FINAL_MOMENTS));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RECENT.clear();
    }

    private static void prune(long now) {
        RECENT.values().removeIf(shot -> !inWindow(shot.gameTime(), now));
    }
}
