package games.brennan.dungeontrain.ship.vs;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * STUB — Phase 1 of the 1.21.1 migration.
 *
 * <p>Valkyrien Skies has no published 1.21.1 / NeoForge build yet (see
 * {@link <a href="https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/1126">VS issue #1126</a>}).
 * This stub satisfies the {@link Shipyard} contract while the rest of the
 * mod is migrated to the new stack. {@code findAll}/{@code findAt} return
 * empty/null so the train code is harmlessly inert; {@code assemble} fails
 * fast so any attempt to spawn a train surfaces the migration state instead
 * of silently no-op'ing.</p>
 *
 * <p>Phase 2 will replace this with a real adapter wrapping VS once
 * upstream ships, restoring the same surface area as the pre-migration
 * implementation.</p>
 */
public final class VsShipyard implements Shipyard {

    private static final String STUB_MESSAGE =
        "VS adapter not yet ported to 1.21.1 — see plans/ticklish-soaring-comet.md (Phase 2). "
            + "Train spawn requires a working physics-mod adapter.";

    @SuppressWarnings("unused")
    private final ServerLevel level;

    public VsShipyard(ServerLevel level) {
        this.level = level;
    }

    @Override
    public ManagedShip assemble(Set<BlockPos> blocks, double density) {
        throw new UnsupportedOperationException(STUB_MESSAGE);
    }

    @Override
    public void delete(ManagedShip ship) {
        // No-op — there are no managed ships to delete in stub mode.
    }

    @Override
    public List<ManagedShip> findAll() {
        return Collections.emptyList();
    }

    @Override
    @Nullable
    public ManagedShip findAt(BlockPos pos) {
        return null;
    }
}
