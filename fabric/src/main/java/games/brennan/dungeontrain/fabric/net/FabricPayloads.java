package games.brennan.dungeontrain.fabric.net;

import games.brennan.dungeontrain.net.DtPayloads;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Fabric mirror of {@code DungeonTrainNet}: iterates the single loader-neutral
 * {@link DtPayloads#ALL} table into Fabric's networking API. Each spec's wire codec
 * is registered with {@link PayloadTypeRegistry} (both directions, both sides — the
 * codec must exist to send AND receive), and its handler is wrapped so it receives a
 * {@link DtPayloadContext} (game-thread execution matching NeoForge's
 * {@code IPayloadContext.enqueueWork}).
 *
 * <p>Split by side: {@link #registerTypesAndServerReceivers()} runs from the common
 * entrypoint (codecs for every payload + C2S server receivers);
 * {@link #registerClientReceivers()} runs from the client entrypoint (S2C client
 * receivers). Registration order in the table carries no protocol meaning — each
 * payload is keyed on the wire by its own {@code CustomPacketPayload.Type} id.</p>
 */
public final class FabricPayloads {

    private FabricPayloads() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /**
     * Common side: register every payload codec + the C2S server receivers.
     *
     * <p><b>Fabric-v1 dedicated-server divergence:</b> building {@code DtPayloads.ALL} materialises
     * every payload handler method reference, and several S2C handlers reference client-only vanilla
     * types (e.g. {@code LocalPlayer}) directly in the (common) packet class. NeoForge loads client
     * classes on a dedicated server; Fabric's Knot classloader refuses, so the table can't build on a
     * dedicated server. We swallow that {@code LinkageError}/{@code RuntimeException} so the dedicated
     * server still boots — S2C/C2S networking is then inert on a dedicated Fabric server (a known v1 gap;
     * the integrated server / physical client loads the table fine and networks normally). Refactoring
     * the client handlers out of the common packet classes is the Stage-5 fix.</p>
     */
    public static void registerTypesAndServerReceivers() {
        try {
            for (DtPayloads.Spec<?> spec : DtPayloads.ALL) {
                registerType(spec);
                if (spec.direction() == DtPayloads.Direction.C2S) {
                    registerServerReceiver(spec);
                }
            }
        } catch (Throwable t) {
            LOG.warn("DT network payload table unavailable on this loader/env ({}); "
                + "networking inert (Fabric-v1 dedicated-server gap).", t.toString());
        }
    }

    private static <T extends CustomPacketPayload> void registerType(DtPayloads.Spec<T> spec) {
        CustomPacketPayload.Type<T> type = spec.type();
        StreamCodec<? super RegistryFriendlyByteBuf, T> codec = spec.codec();
        switch (spec.direction()) {
            case S2C -> PayloadTypeRegistry.playS2C().register(type, codec);
            case C2S -> PayloadTypeRegistry.playC2S().register(type, codec);
        }
    }

    private static <T extends CustomPacketPayload> void registerServerReceiver(DtPayloads.Spec<T> spec) {
        BiConsumer<T, DtPayloadContext> handler = spec.handler();
        ServerPlayNetworking.registerGlobalReceiver(spec.type(),
            (payload, context) -> handler.accept(payload, new FabricPayloadContext(context.player())));
    }
}
