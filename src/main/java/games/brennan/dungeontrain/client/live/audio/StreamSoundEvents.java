package games.brennan.dungeontrain.client.live.audio;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;

/**
 * Tells each sound channel what it is playing (its category and instance) as the engine starts it, so
 * the stream can hear it at full volume whatever the streamer's sliders say ({@link StreamVolume}).
 * Both events fire on the sound thread, right after the channel's buffer is attached and played.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamSoundEvents {

    private StreamSoundEvents() {}

    @SubscribeEvent
    public static void onPlay(PlaySoundSourceEvent event) {
        AudioMirror.played((MirrorTwin.Channel) event.getChannel(), event.getSound());
    }

    @SubscribeEvent
    public static void onPlayStreaming(PlayStreamingSourceEvent event) {
        AudioMirror.played((MirrorTwin.Channel) event.getChannel(), event.getSound());
    }
}
