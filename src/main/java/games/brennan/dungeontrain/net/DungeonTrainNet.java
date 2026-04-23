package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Central network channel for Dungeon Train's client/server messages.
 *
 * <p>Versioning: protocol version is a literal string. A client with a
 * different version will be rejected by Forge's handshake — bump
 * {@link #PROTOCOL_VERSION} any time packet layouts change.</p>
 */
public final class DungeonTrainNet {

    public static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(DungeonTrain.MOD_ID, "main"))
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel();

    private DungeonTrainNet() {}

    /**
     * Register all packet types. Call once during {@code FMLCommonSetupEvent}.
     * IDs are stable across versions — don't renumber, only append.
     */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(VariantHoverPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(VariantHoverPacket::encode)
            .decoder(VariantHoverPacket::decode)
            .consumerMainThread(VariantHoverPacket::handle)
            .add();
        CHANNEL.messageBuilder(CarriageIndexPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(CarriageIndexPacket::encode)
            .decoder(CarriageIndexPacket::decode)
            .consumerMainThread(CarriageIndexPacket::handle)
            .add();
    }

    /** Convenience: send a packet to a single player. */
    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
