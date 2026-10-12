package games.brennan.dungeontrain.compat;

import java.util.UUID;

/**
 * A PlayerMob's pending photo subject — the player it has been asked to photograph. Implemented
 * onto {@code PlayerMobEntity} by {@code mixin.PlayerMobCameraHolderMixin}, set by
 * {@link PlayerMobCameraBridge} when a player gifts the mob a camera, consumed by
 * {@link PlayerMobPhotoGoal}. One subject at a time; session-only (never saved).
 */
public interface PlayerMobPhotoSubject {

    /** The player to photograph, or {@code null} when there is no pending request. */
    UUID dungeontrain$photoSubject();

    /** Set (or, with {@code null}, clear) the pending subject. */
    void dungeontrain$setPhotoSubject(UUID player);
}
