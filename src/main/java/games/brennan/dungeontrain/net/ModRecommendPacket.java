package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.modrec.ModRecState;
import games.brennan.dungeontrain.discord.ModRecommendReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player recommended a mod on the death screen's Mod Recommendations page.
 *
 * <p>Two shapes travel on the same payload. A <b>recommendation</b> names a mod the player is
 * actually running ({@code modId} + {@code displayName} from {@code ModList}, {@code requested}
 * false). A <b>request</b> names a mod they wish were supported ({@code requested} true), where
 * {@code displayName} is what the player typed and {@code modId} is empty — nothing on the client
 * can resolve an id for a mod that isn't installed.</p>
 *
 * <p>The server gates on the same per-player, fail-closed network consent as every other player
 * report ({@link NetworkConsentMirror#isGranted}) and hands off to {@link ModRecommendReporter}.
 * The comment is player free-text: it goes to the private Discord survey channel and nowhere
 * else — see the reporter for the split.</p>
 */
public record ModRecommendPacket(String modId, String displayName, String comment, boolean requested)
        implements CustomPacketPayload {

    public static final Type<ModRecommendPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "mod_recommend"));

    public static final StreamCodec<FriendlyByteBuf, ModRecommendPacket> STREAM_CODEC =
        StreamCodec.of(ModRecommendPacket::encode, ModRecommendPacket::decode);

    private static void encode(FriendlyByteBuf buf, ModRecommendPacket p) {
        buf.writeUtf(p.modId == null ? "" : p.modId, ModRecState.MAX_NAME);
        buf.writeUtf(p.displayName == null ? "" : p.displayName, ModRecState.MAX_NAME);
        buf.writeUtf(p.comment == null ? "" : p.comment, ModRecState.MAX_COMMENT);
        buf.writeBoolean(p.requested);
    }

    private static ModRecommendPacket decode(FriendlyByteBuf buf) {
        String modId = buf.readUtf(ModRecState.MAX_NAME);
        String displayName = buf.readUtf(ModRecState.MAX_NAME);
        String comment = buf.readUtf(ModRecState.MAX_COMMENT);
        boolean requested = buf.readBoolean();
        return new ModRecommendPacket(modId, displayName, comment, requested);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ModRecommendPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // A recommendation is entirely voluntary feedback about the player's own setup, so it
            // rides the same consent gate as the rest of the outbound player data. No consent →
            // drop silently; the client has already thanked them either way.
            if (!NetworkConsentMirror.isGranted(player)) return;
            ModRecommendReporter.report(player, packet);
        });
    }
}
