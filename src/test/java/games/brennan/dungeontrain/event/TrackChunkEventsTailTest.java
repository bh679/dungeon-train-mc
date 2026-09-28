package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a train's tail provider drains its chunk queue, so only the tail should be offered chunks. */
final class TrackChunkEventsTailTest {

    private static final ResourceKey<Level> DIM =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));

    private static TrainTransformProvider provider(UUID train, int pIdx) {
        return new TrainTransformProvider(new Vector3d(0.1, 0, 0), BlockPos.ZERO, DIM, pIdx, 1,
            new CarriageDims(9, 7, 7), train);
    }

    private static ManagedShip ship(KinematicDriver driver) {
        return (ManagedShip) Proxy.newProxyInstance(
            ManagedShip.class.getClassLoader(), new Class<?>[] {ManagedShip.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getKinematicDriver")) return driver;
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    @DisplayName("the lowest pIdx per train is the tail; undriven ships are ignored")
    void lowestPIdxPerTrain() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TrainTransformProvider aTail = provider(a, -18);
        TrainTransformProvider bTail = provider(b, 3);
        List<ManagedShip> ships = List.of(
            ship(provider(a, 0)), ship(aTail), ship(provider(a, 42)),
            ship(provider(b, 9)), ship(bTail), ship(null));
        Collection<TrainTransformProvider> tails = TrackChunkEvents.tailProviders(ships);
        assertEquals(2, tails.size());
        assertTrue(tails.contains(aTail));
        assertTrue(tails.contains(bTail));
    }
}
