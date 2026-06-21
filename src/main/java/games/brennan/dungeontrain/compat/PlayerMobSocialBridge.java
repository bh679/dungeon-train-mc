package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.advancement.PlayerMobSocialTracker;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.playermob.compat.PlayerMobSocialHooks;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Bridges PlayerMob's gift seam ({@link PlayerMobSocialHooks}) to DungeonTrain's
 * befriend-advancement tracker ({@link PlayerMobSocialTracker}).
 *
 * <p>Replaces the old string-targeted {@code PlayerMobGiveItemMixin} /
 * {@code PlayerMobPickupMixin}. PlayerMob's feeling-tiered gift rewrite removed
 * {@code PlayerMobEntity.giveItemTo}, which the GiveItem mixin hooked — with
 * {@code injectors.defaultRequire = 1} that absent injection point crashed mod
 * load against the new PlayerMob. The seam fires {@code onMobGift} from
 * {@code tossGift} (a mob handing the player a gift) and {@code onPlayerGift}
 * from {@code creditGift} (the mob picking up an item the player dropped), so
 * both halves of the exchange are still observed.</p>
 *
 * <p>The hard reference to {@code PlayerMobSocialHooks} lives only inside
 * {@link #install()}, so this class loads even when the seam is absent; the
 * caller ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded}
 * and catches {@link Throwable}, so a PlayerMob build without the seam degrades
 * to "no befriend advancements" instead of a crash.</p>
 */
public final class PlayerMobSocialBridge {

    private PlayerMobSocialBridge() {}

    /** Subscribe the befriend tracker to PlayerMob's gift seam. */
    public static void install() {
        PlayerMobSocialHooks.install(new PlayerMobSocialHooks.GiftObserver() {
            @Override
            public void onMobGift(ServerPlayer recipient, UUID mobId) {
                // A PlayerMob gifted the player an item (tossGift). mob -> player.
                PlayerMobSocialTracker.recordMobGift(recipient, mobId);
                RemoteEchoEncounters.onReceivedGift(recipient, mobId);
            }

            @Override
            public void onPlayerGift(ServerPlayer giver, UUID mobId) {
                // The player gave a PlayerMob an item it picked up (creditGift). player -> mob.
                PlayerMobSocialTracker.recordPlayerGift(giver, mobId);
                RemoteEchoEncounters.onGaveGift(giver, mobId);
            }
        });
    }
}
