package games.brennan.dungeontrain.fabric.client;

import games.brennan.dungeontrain.fabric.net.FabricPayloadContext;
import games.brennan.dungeontrain.net.DtPayloads;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import java.util.function.BiConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-only S2C receiver registration, split out of {@code FabricPayloads} so the
 * common (server-loaded) part never references client types. The receiver handler reads
 * {@code context.player()} — a {@code LocalPlayer} value flowing into the
 * {@link FabricPayloadContext} {@code Player} — which forces the JVM verifier to load
 * {@code LocalPlayer}; on a dedicated server that class is refused, so this code must live
 * in an {@code @Environment(CLIENT)} class only ever touched from the client entrypoint.
 */
@Environment(EnvType.CLIENT)
public final class FabricClientPayloads {

    private FabricClientPayloads() {}

    /** Client side: register the S2C client receivers (codecs already registered common-side). */
    public static void registerClientReceivers() {
        for (DtPayloads.Spec<?> spec : DtPayloads.ALL) {
            if (spec.direction() == DtPayloads.Direction.S2C) {
                registerClientReceiver(spec);
            }
        }
    }

    private static <T extends CustomPacketPayload> void registerClientReceiver(DtPayloads.Spec<T> spec) {
        BiConsumer<T, DtPayloadContext> handler = spec.handler();
        ClientPlayNetworking.registerGlobalReceiver(spec.type(),
            (payload, context) -> handler.accept(payload, new FabricPayloadContext(context.player())));
    }
}
