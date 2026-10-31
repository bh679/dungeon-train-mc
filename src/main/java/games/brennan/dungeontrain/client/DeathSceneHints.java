package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DeathScenePacket;

/** The latest {@link DeathScenePacket}, read by {@link DeathCameraCycle} when it starts. */
public final class DeathSceneHints {

    private static DeathScenePacket latest = new DeathScenePacket(false, DeathScenePacket.NONE, DeathScenePacket.NONE);

    private DeathSceneHints() {}

    public static void set(DeathScenePacket packet) {
        latest = packet == null ? new DeathScenePacket(false, DeathScenePacket.NONE, DeathScenePacket.NONE) : packet;
    }

    public static DeathScenePacket latest() {
        return latest;
    }

    /** Forget a death's hints once its scene has used them, so a later death without a packet starts clean. */
    public static void clear() {
        set(null);
    }
}
