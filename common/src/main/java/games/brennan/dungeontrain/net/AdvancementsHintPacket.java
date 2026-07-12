package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.client.AdvancementsHintClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Server → client: the local player just earned a Dungeon Train <em>gameplay</em>
 * advancement. Carries no data — the decision of whether to surface the
 * advancements-keybind hint, and the keybind string itself, both live on the
 * client (the "has opened advancements" flag is a client-only config value and
 * the keybind is a client-only option). So the server fires this unconditionally
 * for every qualifying earn and the client suppresses it when appropriate. See
 * {@link AdvancementsHintClient}.
 *
 * <p>Empty payload — encoded via {@link StreamCodec#unit} (mirrors
 * {@code StartingBookClosedPacket} / {@code CinematicDonePacket}).</p>
 *
 * <p>Sent from {@code AchievementEvents.onAdvancementEarn} for every
 * {@code dungeontrain:} advancement whose path is not under {@code editor/},
 * except during the login-replay loop (which would otherwise re-fire the hint
 * for every previously-earned advancement).</p>
 */
public record AdvancementsHintPacket() implements CustomPacketPayload {

    public static final Type<AdvancementsHintPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "advancements_hint"));

    public static final StreamCodec<FriendlyByteBuf, AdvancementsHintPacket> STREAM_CODEC =
        StreamCodec.unit(new AdvancementsHintPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the
     * reference to {@link AdvancementsHintClient} (a client-package class) is
     * safe (mirrors {@code CinematicIntroPacket.handle}).
     */
    public static void handle(AdvancementsHintPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(AdvancementsHintClient::queueHint);
    }
}
