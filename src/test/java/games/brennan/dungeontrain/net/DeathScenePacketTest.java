package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathScenePacketTest {

    @Test
    void roundTripsHintsAndTargets() {
        UUID a = UUID.randomUUID();
        DeathScenePacket in = new DeathScenePacket(false, 42, -1, List.of(
            new DeathScenePacket.Target(a, 20481030.5, 70.25, 20481037.5, DeathScenePacket.Target.Kind.CONTENTS),
            new DeathScenePacket.Target(a, 20481040.0, 72.0, 20481037.5, DeathScenePacket.Target.Kind.GROUP)),
            new net.minecraft.world.phys.Vec3(512.5, 79.5, 3.5));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        in.encode(buf);
        DeathScenePacket out = DeathScenePacket.decode(buf);
        assertEquals(in, out);
    }

    @Test
    void noTargetsRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new DeathScenePacket(true, -1, 7).encode(buf);
        assertEquals(new DeathScenePacket(true, -1, 7), DeathScenePacket.decode(buf));
    }
}
