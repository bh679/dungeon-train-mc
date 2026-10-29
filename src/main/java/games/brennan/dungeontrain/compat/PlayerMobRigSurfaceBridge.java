package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.RigSurfaces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Bridges PlayerMob's {@link RigSurfaces} seam so a PlayerMob riding a carriage builds its End
 * Crystal bunker <em>on</em> the carriage.
 *
 * <p>Carriage blocks live only in their Sable plot, so PlayerMob's own world-space rig code reads a
 * carriage deck as air (no floor, no rig) and would leave anything it placed floating in the world as
 * the train drove off. The surface installed here maps the rig into the plot of the carriage under the
 * mob instead: reads go through {@link CarriageDeck#blockInPlot}, writes land at the plot cell, and the
 * crystal is spawned over the plot obsidian, where {@code TrainStaticContentsCarrier} adopts it and
 * carries it with the carriage.</p>
 *
 * <p>Rig blocks are marked as passenger-built ({@link PlayerPlacedTrainBlocks}), like any block a
 * player bolts on: they break off against terrain rather than ploughing it, and mobs may dig them.</p>
 *
 * <p>Mirrors {@link FreePlayBridge}: the hard reference to {@code RigSurfaces} lives only inside
 * {@link #install()}, and the caller ({@code DungeonTrain.commonSetup}) gates on
 * {@code ModList.isLoaded} and catches {@link Throwable}, so a PlayerMob build predating the seam
 * degrades to world-space rigs rather than a crash.</p>
 */
public final class PlayerMobRigSurfaceBridge {

    private PlayerMobRigSurfaceBridge() {}

    /** Install the carriage rig-surface provider. */
    public static void install() {
        RigSurfaces.install(new RigSurfaces.Provider() {
            @Override
            public RigSurfaces.RigSurface surfaceFor(Mob mob) {
                if (!(mob.level() instanceof ServerLevel level)) return null;
                Trains.Carriage carriage = CarriageDeck.carriageUnder(Trains.allCarriages(level), mob);
                if (carriage == null || !(carriage.ship() instanceof SableManagedShip ship)
                    || ship.subLevel() == null) {
                    return null;
                }
                return new CarriageSurface(level, ship);
            }
        });
    }

    /** The plot of one carriage, as a rig surface. Local cells are plot cells. */
    private record CarriageSurface(ServerLevel level, SableManagedShip ship) implements RigSurfaces.RigSurface {
        @Override
        public BlockPos toLocal(BlockPos worldPos) {
            return CarriageDeck.shipLocal(ship, worldPos);
        }

        @Override
        public BlockState getBlockState(BlockPos local) {
            return CarriageDeck.blockInPlot(ship.subLevel().getPlot(), local);
        }

        @Override
        public boolean isSturdyFloor(BlockPos local) {
            return getBlockState(local).isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
        }

        @Override
        public void setBlock(BlockPos local, BlockState state) {
            // The plot write path PlayerBlockBreakOff uses; the mark goes on after, since a block-type
            // change clears any mark at the cell.
            if (level.setBlock(local, state, Block.UPDATE_ALL)) {
                PlayerPlacedTrainBlocks.mark(ship.subLevel(), local.immutable());
            }
        }

        @Override
        public Vec3 toWorld(double x, double y, double z) {
            // shipToWorld mutates its argument; hand it a throwaway.
            Vector3d world = ship.shipToWorld(new Vector3d(x, y, z));
            return new Vec3(world.x, world.y, world.z);
        }
    }
}
