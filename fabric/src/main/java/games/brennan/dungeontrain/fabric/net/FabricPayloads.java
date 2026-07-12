package games.brennan.dungeontrain.fabric.net;

import games.brennan.dungeontrain.net.DtPayloads;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

    /** Common side: register every payload codec + the C2S server receivers. */
    public static void registerTypesAndServerReceivers() {
        for (DtPayloads.Spec<?> spec : DtPayloads.ALL) {
            registerType(spec);
            if (spec.direction() == DtPayloads.Direction.C2S) {
                registerServerReceiver(spec);
            }
        }
    }

    /** Client side: register the S2C client receivers (codecs already registered above). */
    public static void registerClientReceivers() {
        for (DtPayloads.Spec<?> spec : DtPayloads.ALL) {
            if (spec.direction() == DtPayloads.Direction.S2C) {
                registerClientReceiver(spec);
            }
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

    private static <T extends CustomPacketPayload> void registerClientReceiver(DtPayloads.Spec<T> spec) {
        BiConsumer<T, DtPayloadContext> handler = spec.handler();
        ClientPlayNetworking.registerGlobalReceiver(spec.type(),
            (payload, context) -> handler.accept(payload, new FabricPayloadContext(context.player())));
    }
}
