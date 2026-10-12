package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets ReForgedPlay's <b>Quick Mode</b> load a replay on a NeoForge client.
 *
 * <p>Quick Mode rebuilds the world from the recorded registries but synthesises the configuration
 * phase itself, without NeoForge's modded handshake payloads. NeoForge therefore treats the
 * replay's "server" as vanilla and {@code NetworkRegistry.initializeOtherConnection(Client…)}
 * disconnects on the first of three checks a modded client makes against a vanilla server —
 * channel negotiation, extensible enums, feature flags — leaving the player on the
 * "Reconfiguring" screen forever. (Normal playback replays the recorded handshake and never
 * reaches this code.)</p>
 *
 * <p>While {@link ReplayModRecordingProbe#isReplaying()} all three answer "fine, continue", so
 * configuration completes on the vanilla-server path and the world loads — which is correct for
 * a replay: it only ever carries vanilla packets, and the client reads registries and feature
 * flags from its own installation. Outside playback the calls are untouched, so a real vanilla
 * server is still rejected exactly as NeoForge intends.</p>
 *
 * <p>{@code remap = false}: NeoForge's own names. Bytecode-verified against
 * {@code neoforge 21.1.230} ({@code universal}): the {@code ClientConfigurationPacketListener}
 * overload calls {@code NegotiationResult.success()} once (offset 105) and each
 * {@code handleVanillaServerConnection} once (144, 152). The explicit descriptor keeps the
 * {@code ServerConfigurationPacketListener} overload out. <b>Re-verify on any NeoForge bump.</b></p>
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryReplayPlaybackMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.replay");

    @Unique
    private static final String dungeontrain$TARGET =
        "initializeOtherConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;)V";

    @WrapOperation(
        method = dungeontrain$TARGET,
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/negotiation/NegotiationResult;success()Z")
    )
    private static boolean dungeontrain$negotiationPassesDuringReplay(NegotiationResult result, Operation<Boolean> original) {
        if (ReplayModRecordingProbe.isReplaying()) {
            dungeontrain$LOGGER.info("[DungeonTrain] Replay playback: continuing as a vanilla connection past NeoForge's negotiation checks");
            return true;
        }
        return original.call(result);
    }

    @WrapOperation(
        method = dungeontrain$TARGET,
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/configuration/CheckExtensibleEnums;"
                + "handleVanillaServerConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;)Z")
    )
    private static boolean dungeontrain$enumsPassDuringReplay(ClientConfigurationPacketListener listener, Operation<Boolean> original) {
        if (ReplayModRecordingProbe.isReplaying()) return true;
        return original.call(listener);
    }

    @WrapOperation(
        method = dungeontrain$TARGET,
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/configuration/CheckFeatureFlags;"
                + "handleVanillaServerConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;)Z")
    )
    private static boolean dungeontrain$flagsPassDuringReplay(ClientConfigurationPacketListener listener, Operation<Boolean> original) {
        if (ReplayModRecordingProbe.isReplaying()) return true;
        return original.call(listener);
    }
}
