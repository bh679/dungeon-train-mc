package games.brennan.dungeontrain.ship.sable;

/**
 * Duck-type interface mixed into Sable's {@code ServerSubLevel} (by
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelFreezeMixin}) so DT can carry a
 * per-body "physics frozen" flag <em>on the instance itself</em> — O(1) to read, no external
 * map to key/clean, and it dies with the sub-level when Sable removes it.
 *
 * <p><b>"Frozen"</b> means DT has {@code pipeline.remove()}d the carriage's Rapier body from
 * the shared physics scene (issue #646) while keeping the sub-level fully <em>loaded</em>
 * (chunks, blocks, {@code logicalPose} all in memory). This is <em>not</em> the cull→reload
 * path that caused the jitter/vanish bugs (#628/#630/#623) — only the physics body is dropped.</p>
 *
 * <p>The flag is read on the hot physics path ({@code isFrozen} is called ~O(bodies × substeps)
 * times per tick from the reader mixins), so it must stay a plain field read — hence the
 * mixin-added field rather than a lookup. {@link #dt$inactiveTicks()} carries the freeze
 * hysteresis counter (ticks a carriage has been continuously inactive) on the same instance.</p>
 *
 * <p>Read/written only on the server thread (physics tick + the post-tick reconcile in
 * {@link games.brennan.dungeontrain.event.TrainTickEvents}), which never overlap, so no
 * synchronisation is needed. See {@link PhysicsFreeze} / {@link PhysicsFreezeController}.</p>
 */
public interface DtFreezable {

    /** True while DT has removed this sub-level's Rapier body from the physics scene. */
    boolean dt$isPhysicsFrozen();

    /** Set by {@link PhysicsFreeze#freeze}/{@link PhysicsFreeze#unfreeze} around the pipeline op. */
    void dt$setPhysicsFrozen(boolean frozen);

    /** Consecutive ticks this carriage has been inactive (untracked, no live entity aboard). */
    int dt$inactiveTicks();

    /** Updated each reconcile tick by {@link PhysicsFreezeController}. */
    void dt$setInactiveTicks(int ticks);

    /**
     * True iff this sub-level's body is currently in the Rapier scene. Tracked authoritatively by
     * {@code RapierPipelineFreezeMixin} on every {@code pipeline.add}/{@code remove} — for Sable's
     * own lifecycle (spawn/cull/recover) as well as DT's freeze/unfreeze. {@link PhysicsFreeze} uses
     * it to keep the write ops idempotent: never {@code remove} a body that is already out, never
     * {@code add} one that is already in — the fix for the {@code removeSubLevel} native abort that
     * fired when DT froze a carriage the appender was mid-spawn. Distinct from
     * {@link #dt$isPhysicsFrozen()} (DT <em>intent</em>, which gates the readers): a freshly-spawned
     * carriage Sable hasn't added yet is {@code !inScene} but not DT-frozen. Defaults false.
     */
    boolean dt$isInScene();

    void dt$setInScene(boolean inScene);
}
