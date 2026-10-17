package games.brennan.dungeontrain.client.live;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.world.entity.player.Player;

/**
 * Whether this client could go live if a Live Eye landed on its head: the reads shared by everything that
 * hands one over (a dead streamer's takeover, {@link LiveTakeoverOffer}; a streamer's gift,
 * {@link LiveGiftClient}). They mirror what stops a stream once the Live Eye is on, so nobody is handed
 * a stream they cannot carry.
 */
final class LiveEyeEligibility {

    private LiveEyeEligibility() {}

    /** A Free Play run never goes live; dev builds stream in it for testing, as the server allows (LiveFeedEvents#streamBlocked). */
    static boolean freePlayBlocks(Player player) {
        return player.hasEffect(ModMobEffects.FREE_PLAY) && !DungeonTrain.isDevBuild();
    }

    /** Live Eye streaming switched on, behind the internet consent. */
    static boolean streamingAllowed() {
        return DiscordPresenceClientConfig.isGranted() && LiveFeedClientConfig.streamingEnabled();
    }

    /** ffmpeg is there, or still being fetched — not switched off or failed. */
    static boolean ffmpegUsable() {
        FfmpegSupport.State state = FfmpegSupport.state();
        return state != FfmpegSupport.State.OFF && state != FfmpegSupport.State.FAILED;
    }
}
