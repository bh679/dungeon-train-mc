package games.brennan.dungeontrain.ship.sable;

/**
 * Duck-type interface mixed into Sable's {@code ServerSubLevel} (by
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelFreezeMixin}) so DT can carry a
 * per-body "physics frozen" flag <em>on the instance itself</em> — O(1) to read, no external
 * map to key/clean, and it dies with the sub-level when Sable removes it.
 *
 * <p><b>"Frozen"</b> (soft-freeze, issue #646) means DT has parked this carriage's kinematic body
 * at rest and flagged it so the reader mixins skip Sable's per-body Java work and
 * {@link SableManagedShip#applyTickOutput} stops teleporting it. The body stays fully <em>in</em> the
 * Rapier scene (valid, queryable) and the sub-level stays <em>loaded</em> — nothing is removed, so
 * there is no uncatchable-abort surface. This is <em>not</em> the cull→reload path that caused the
 * jitter/vanish bugs (#628/#630/#623); only per-body physics work is skipped.</p>
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

    /** True while DT has soft-frozen (parked) this sub-level's physics body. */
    boolean dt$isPhysicsFrozen();

    /** Set by {@link PhysicsFreeze#freeze}/{@link PhysicsFreeze#unfreeze}. */
    void dt$setPhysicsFrozen(boolean frozen);

    /** Consecutive ticks this carriage has been inactive (untracked, no live entity aboard). */
    int dt$inactiveTicks();

    /** Updated each reconcile tick by {@link PhysicsFreezeController}. */
    void dt$setInactiveTicks(int ticks);
}
