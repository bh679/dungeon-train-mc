package games.brennan.dungeontrain.net;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.net.platform.DtNetSender;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Central network registrar for Dungeon Train's client/server payloads.
 *
 * <p>This is the NeoForge bridge — it doesn't know packet-by-packet details,
 * it just iterates the loader-neutral {@link DtPayloads#ALL} table and wires
 * each entry into NeoForge's {@link PayloadRegistrar}, wrapping every handler
 * so it receives a {@link DtPayloadContext} instead of the real {@code
 * IPayloadContext} (see {@link NeoForgePayloadContext}). Sends go through
 * {@link DtNetSender}, whose NeoForge impl ({@link NeoForgeNetSender}) is
 * registered via {@code META-INF/services} in this module's resources.</p>
 *
 * <p>Versioning: protocol version is a literal string. NeoForge's payload
 * handshake uses this to reject mismatched clients — bump
 * {@link #PROTOCOL_VERSION} any time packet layouts change.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class DungeonTrainNet {

    public static final String PROTOCOL_VERSION = "45";

    private DungeonTrainNet() {}

    /**
     * Register all payload types. Triggered by NeoForge's mod-bus
     * {@link RegisterPayloadHandlersEvent}. IDs are stable across versions —
     * don't rename payload types, only append new ones (see {@link DtPayloads}).
     */
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(DtCore.MOD_ID).versioned(PROTOCOL_VERSION);

        for (DtPayloads.Spec<?> spec : DtPayloads.ALL) {
            registerOne(registrar, spec);
        }
    }

    private static <T extends CustomPacketPayload> void registerOne(
            PayloadRegistrar registrar, DtPayloads.Spec<T> spec) {
        CustomPacketPayload.Type<T> type = spec.type();
        StreamCodec<? super RegistryFriendlyByteBuf, T> codec = spec.codec();
        var handler = spec.handler();

        switch (spec.direction()) {
            case S2C -> registrar.playToClient(type, codec,
                (payload, ctx) -> handler.accept(payload, new NeoForgePayloadContext(ctx)));
            case C2S -> registrar.playToServer(type, codec,
                (payload, ctx) -> handler.accept(payload, new NeoForgePayloadContext(ctx)));
        }
    }

    /** Convenience: send a payload to the server (client → server). */
    public static void sendToServer(CustomPacketPayload payload) {
        DtNetSender.get().sendToServer(payload);
    }

    /** Convenience: send a payload to a single player. */
    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        DtNetSender.get().sendToPlayer(player, payload);
    }
}
