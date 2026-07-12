package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtNetSender;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NeoForge-backed {@link DtNetSender}, registered for {@link
 * java.util.ServiceLoader} lookup via {@code META-INF/services} in this
 * module's resources. Pure delegation to {@code PacketDistributor} — no
 * behavior change from the pre-seam callsites.
 */
public final class NeoForgeNetSender implements DtNetSender {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToAllPlayers(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
    }
}
