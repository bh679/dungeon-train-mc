package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Tells the server the player just changed the train engine volume, so it can
 * award "Sound Check". The setting is client-side config
 * ({@link games.brennan.dungeontrain.config.ClientDisplayConfig#setTrainEngineVolume})
 * with no server-visible effect, so the client has to say so.
 *
 * <p>Called from the setter itself rather than from each UI surface — the DT
 * options stepper, the sound-screen slider and the pause-menu slider all funnel
 * through it, and it already skips unchanged writes, so "the setter actually
 * changed the value" is exactly the signal wanted. One packet per connection is
 * enough (the advancement is a one-shot marker); the connection identity is
 * remembered so joining another world can still earn it there.</p>
 */
public final class TrainVolumeAdvancement {

    /** The connection this client last reported a change on, or {@code null}. */
    private static ClientPacketListener reportedOn;

    private TrainVolumeAdvancement() {}

    /** Report a real volume change, at most once per connection. Silent in the main menu. */
    public static void onVolumeChanged() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || connection == reportedOn) return;
        reportedOn = connection;
        DungeonTrainNet.sendToServer(new ClientActionPacket("changed_engine_volume"));
    }
}
