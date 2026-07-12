package games.brennan.dungeontrain.fabric.net;

import games.brennan.dungeontrain.net.platform.DtNetSender;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric-backed {@link DtNetSender}, registered via {@code META-INF/services}. The
 * Fabric mirror of {@code NeoForgeNetSender} (which delegates to
 * {@code PacketDistributor}). {@link #sendToServer} uses the client-only
 * {@code ClientPlayNetworking} — it is only ever reached from client code, so the
 * class links lazily and never touches that method on a dedicated server.
 */
public final class FabricNetSender implements DtNetSender {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToAllPlayers(CustomPacketPayload payload) {
        net.minecraft.server.MinecraftServer server =
            games.brennan.dungeontrain.platform.DtPlatform.get().getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : PlayerLookup.all(server)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
