package games.brennan.dungeontrain.ship.sable;

/**
 * Duck interface implemented onto Sable's {@code dev.ryanhcode.sable.util.LevelAccelerator}
 * by {@code LevelAcceleratorNoSyncLoadMixin}.
 *
 * <p>When the flag is set, the accelerator's chunk lookups become <b>non-loading</b>: a chunk
 * that isn't fully loaded is treated as an empty (all-air) chunk instead of being synchronously
 * loaded/generated on the server thread. Set only on the throwaway accelerator instance that
 * {@code SubLevelEntityCollision.collide} builds per entity-collision sweep — see
 * {@code SubLevelEntityCollisionNoLoadMixin} for the rationale (multi-second main-thread
 * worldgen stalls from item entities colliding against unloaded train plot chunks).</p>
 */
public interface NoSyncLoadChunkAccess {

    /** Marks this accelerator as non-loading: missing chunks read as air instead of sync-loading. */
    void dungeontrain$setNoSyncLoad(boolean noSyncLoad);
}
