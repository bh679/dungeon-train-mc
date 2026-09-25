package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.FlySpeedCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Dev-build default for creative fly speed: in a dev environment ({@code ./gradlew runClient} /
 * {@code runServer}) every player flies at {@link #DEV_MULTIPLIER}× vanilla, so crossing bands
 * while testing doesn't crawl. Production builds keep vanilla speed.
 *
 * <p>Only a player still at vanilla speed is bumped — fly speed is saved with the player, so a
 * value picked with {@code /dt flyspeed} survives a relog. Respawn is hooked too because it
 * builds a fresh player whose abilities start at vanilla.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DevFlySpeedDefaults {

    static final float DEV_MULTIPLIER = 5f;

    private DevFlySpeedDefaults() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        applyDevDefault(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        applyDevDefault(event.getEntity());
    }

    private static void applyDevDefault(Player entity) {
        if (FMLEnvironment.production) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (player.getAbilities().getFlyingSpeed() != FlySpeedCommand.VANILLA_FLY_SPEED) return;
        FlySpeedCommand.setMultiplier(player, DEV_MULTIPLIER);
    }
}
