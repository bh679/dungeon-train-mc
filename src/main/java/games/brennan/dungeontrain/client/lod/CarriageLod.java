package games.brennan.dungeontrain.client.lod;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

/**
 * Performs a single carriage's LOD tier change. {@link CarriageLodController} decides <em>which</em>
 * carriages; this class does the flip. Client-side mirror of
 * {@link games.brennan.dungeontrain.ship.sable.PhysicsFreeze}, which plays the same role for the
 * server-side physics soft-freeze.
 *
 * <h2>What a demote does — and deliberately does not do</h2>
 *
 * <p>Demoting sets the {@link DtLodRenderable} flag and ensures a baked mesh exists. The flag makes
 * DT's gate mixins skip Sable's per-sub-level chunk work for this carriage — section compilation,
 * frustum culling, the {@code renderSectionLayer} draw, and block-entity rendering — and
 * {@link CarriageMeshRenderer} draws the baked mesh in its place.</p>
 *
 * <p><b>It does not close the sub-level's {@code SubLevelRenderData}.</b> That would reclaim the
 * section VBOs, but Sable keeps calling into that object from paths DT does not gate —
 * {@code setDirty} on every block change, {@code resize} when the plot grows,
 * {@code isSectionCompiled} from the vanilla renderer — and handing those a closed object is a
 * crash surface for a memory saving we have not yet measured as needed. Leaving it open is also
 * what makes promotion cheap and correct: block changes keep marking sections dirty while the
 * carriage is far, so promoting simply resumes compiling and the sections rebuild themselves. No
 * cache to invalidate, no state to reconcile.</p>
 *
 * <p>Reclaiming that memory is a worthwhile follow-up, but it needs its own gating of the
 * {@code setDirty}/{@code resize}/{@code isSectionCompiled} callers. Not v1.</p>
 *
 * <p><b>Nothing here touches the server.</b> The sub-level stays tracked, loaded and physically
 * simulated exactly as before; this is purely a client-side choice about how to draw it. That is
 * what makes the {@code /dungeontrain debug lod off} toggle a complete and instant revert.</p>
 */
public final class CarriageLod {

    private CarriageLod() {}

    /** True while this sub-level is rendering as a baked mesh instead of chunk sections. */
    public static boolean isFar(ClientSubLevel sl) {
        return sl instanceof DtLodRenderable f && f.dt$isLodFar();
    }

    /**
     * Demote {@code sl} to the baked-mesh tier. Requests the bake <em>before</em> setting the flag
     * so there is never a frame where the chunk render is gated off but no mesh is ready to stand
     * in for it — that would blink the carriage out of existence. If the bake cannot be produced
     * (blocks not present yet), the flag is left clear and the carriage stays NEAR; the controller
     * will simply try again next tick. Idempotent.
     *
     * @return true if the carriage is now at the far tier; false if it had to stay near.
     */
    public static boolean demote(ClientSubLevel sl) {
        if (!(sl instanceof DtLodRenderable flag)) return false;
        if (flag.dt$isLodFar()) return true;
        if (!CarriageMeshCache.ensureBaked(sl)) return false; // no mesh yet — stay NEAR, retry next tick
        flag.dt$setLodFar(true);
        return true;
    }

    /**
     * Promote {@code sl} back to the full chunk render. Clears the flag first so Sable's compile
     * pass is ungated from this tick onward, then drops the baked mesh. Idempotent.
     */
    public static void promote(ClientSubLevel sl) {
        if (!(sl instanceof DtLodRenderable flag)) return;
        if (!flag.dt$isLodFar()) {
            CarriageMeshCache.drop(sl);
            return;
        }
        flag.dt$setLodFar(false);
        CarriageMeshCache.drop(sl);
    }
}
