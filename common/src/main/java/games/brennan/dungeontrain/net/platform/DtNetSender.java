package games.brennan.dungeontrain.net.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ServiceLoader;

/**
 * Loader-neutral stand-in for NeoForge's {@code PacketDistributor}, sized to
 * exactly the three send operations Dungeon Train uses: server→one-player,
 * client→server, and server→all-players (broadcast).
 *
 * <p>Resolved once via {@link ServiceLoader}; the NeoForge module registers
 * its {@code PacketDistributor}-backed implementation via
 * {@code META-INF/services/games.brennan.dungeontrain.net.platform.DtNetSender}
 * in its own resources (not {@code :common}'s, since the impl is loader-specific).
 * A future Fabric module registers an equivalent impl over Fabric API's networking.</p>
 */
public interface DtNetSender {

    /** Client → server: send a payload the server registered via C2S. */
    void sendToServer(CustomPacketPayload payload);

    /** Server → one player: send a payload that player registered via S2C. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Server → every connected player: broadcast a payload registered via S2C. */
    void sendToAllPlayers(CustomPacketPayload payload);

    /** The loader-provided singleton, resolved lazily on first use. */
    static DtNetSender get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final DtNetSender INSTANCE = load();

        private Holder() {}

        private static DtNetSender load() {
            ServiceLoader<DtNetSender> loader =
                ServiceLoader.load(DtNetSender.class, DtNetSender.class.getClassLoader());
            for (DtNetSender impl : loader) {
                return impl;
            }
            throw new IllegalStateException(
                "No DtNetSender implementation found via ServiceLoader — the loader module "
                    + "must provide META-INF/services/" + DtNetSender.class.getName());
        }
    }
}
