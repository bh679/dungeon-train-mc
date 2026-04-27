package games.brennan.dungeontrain.ship.vs;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Valkyrien Skies adapter for {@link Shipyard}. The only place outside
 * {@code ship.vs.*} that should reference {@code org.valkyrienskies.*}
 * directly is the wiring in {@code Shipyards.of(...)}.
 *
 * <p>Lifetime is per-call (constructed in {@code Shipyards.of}); holds
 * only a {@link ServerLevel} reference. Single-impl façade — JIT inlines
 * port method calls trivially.</p>
 */
public final class VsShipyard implements Shipyard {

    private final ServerLevel level;

    public VsShipyard(ServerLevel level) {
        this.level = level;
    }

    @Override
    public ManagedShip assemble(Set<BlockPos> blocks, double density) {
        ServerShip ship = ShipAssembler.assembleToShip(level, blocks, density);
        return new VsManagedShip(level, ship);
    }

    @Override
    public void delete(ManagedShip ship) {
        if (ship instanceof VsManagedShip vs) {
            ShipAssembler.INSTANCE.deleteShip(level, vs.wrappedAsServerShip(), true, false);
        }
    }

    @Override
    public List<ManagedShip> findAll() {
        List<ManagedShip> result = new ArrayList<>();
        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            result.add(new VsManagedShip(level, ship));
        }
        return result;
    }

    @Override
    @Nullable
    public ManagedShip findAt(BlockPos pos) {
        ServerShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, pos);
        if (ship instanceof LoadedServerShip loaded) {
            return new VsManagedShip(level, loaded);
        }
        if (ship == null) {
            return null;
        }
        return new VsManagedShip(level, ship);
    }
}
