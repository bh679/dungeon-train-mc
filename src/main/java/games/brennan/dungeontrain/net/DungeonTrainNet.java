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

    public static final String PROTOCOL_VERSION = "11";

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
        CHANNEL.messageBuilder(EditorStatusPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(EditorStatusPacket::encode)
            .decoder(EditorStatusPacket::decode)
            .consumerMainThread(EditorStatusPacket::handle)
            .add();
        CHANNEL.messageBuilder(VariantHotkeyPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(VariantHotkeyPacket::encode)
            .decoder(VariantHotkeyPacket::decode)
            .consumerMainThread(VariantHotkeyPacket::handle)
            .add();
        CHANNEL.messageBuilder(PartAssignmentSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(PartAssignmentSyncPacket::encode)
            .decoder(PartAssignmentSyncPacket::decode)
            .consumerMainThread(PartAssignmentSyncPacket::handle)
            .add();
        CHANNEL.messageBuilder(PartAssignmentEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(PartAssignmentEditPacket::encode)
            .decoder(PartAssignmentEditPacket::decode)
            .consumerMainThread(PartAssignmentEditPacket::handle)
            .add();
        CHANNEL.messageBuilder(PartMenuTogglePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(PartMenuTogglePacket::encode)
            .decoder(PartMenuTogglePacket::decode)
            .consumerMainThread(PartMenuTogglePacket::handle)
            .add();
        CHANNEL.messageBuilder(BlockVariantSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(BlockVariantSyncPacket::encode)
            .decoder(BlockVariantSyncPacket::decode)
            .consumerMainThread(BlockVariantSyncPacket::handle)
            .add();
        CHANNEL.messageBuilder(BlockVariantEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(BlockVariantEditPacket::encode)
            .decoder(BlockVariantEditPacket::decode)
            .consumerMainThread(BlockVariantEditPacket::handle)
            .add();
        CHANNEL.messageBuilder(BlockVariantMenuTogglePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(BlockVariantMenuTogglePacket::encode)
            .decoder(BlockVariantMenuTogglePacket::decode)
            .consumerMainThread(BlockVariantMenuTogglePacket::handle)
            .add();
        CHANNEL.messageBuilder(BlockVariantLockIdsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(BlockVariantLockIdsPacket::encode)
            .decoder(BlockVariantLockIdsPacket::decode)
            .consumerMainThread(BlockVariantLockIdsPacket::handle)
            .add();
        CHANNEL.messageBuilder(BlockVariantOutlinePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(BlockVariantOutlinePacket::encode)
            .decoder(BlockVariantOutlinePacket::decode)
            .consumerMainThread(BlockVariantOutlinePacket::handle)
            .add();
        CHANNEL.messageBuilder(ContainerHotkeyPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ContainerHotkeyPacket::encode)
            .decoder(ContainerHotkeyPacket::decode)
            .consumerMainThread(ContainerHotkeyPacket::handle)
            .add();
        CHANNEL.messageBuilder(ContainerContentsMenuTogglePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ContainerContentsMenuTogglePacket::encode)
            .decoder(ContainerContentsMenuTogglePacket::decode)
            .consumerMainThread(ContainerContentsMenuTogglePacket::handle)
            .add();
        CHANNEL.messageBuilder(ContainerContentsSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ContainerContentsSyncPacket::encode)
            .decoder(ContainerContentsSyncPacket::decode)
            .consumerMainThread(ContainerContentsSyncPacket::handle)
            .add();
        CHANNEL.messageBuilder(ContainerContentsEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ContainerContentsEditPacket::encode)
            .decoder(ContainerContentsEditPacket::decode)
            .consumerMainThread(ContainerContentsEditPacket::handle)
            .add();
        CHANNEL.messageBuilder(PrefabRegistrySyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(PrefabRegistrySyncPacket::encode)
            .decoder(PrefabRegistrySyncPacket::decode)
            .consumerMainThread(PrefabRegistrySyncPacket::handle)
            .add();
        CHANNEL.messageBuilder(SaveBlockVariantPrefabPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(SaveBlockVariantPrefabPacket::encode)
            .decoder(SaveBlockVariantPrefabPacket::decode)
            .consumerMainThread(SaveBlockVariantPrefabPacket::handle)
            .add();
        CHANNEL.messageBuilder(SaveLootPrefabPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(SaveLootPrefabPacket::encode)
            .decoder(SaveLootPrefabPacket::decode)
            .consumerMainThread(SaveLootPrefabPacket::handle)
            .add();
    }

    /** Convenience: send a packet to the server (client → server). */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Convenience: send a packet to a single player. */
    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
