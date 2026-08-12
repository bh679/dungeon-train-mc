package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalPuppetAttack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player swung at a puppet, and wants the hit to land on the entity it stands
 * for in the other corridor.
 *
 * <p>A puppet is a drawing rather than an entity, so no vanilla packet can describe this — and the
 * vanilla interact packet would be refused anyway, because it reach-checks the attacker against a
 * target that is forty-odd blocks below in a different copy.</p>
 *
 * <p><b>The id is a claim, not an instruction.</b> All that travels is the source entity's id; the
 * server re-derives the pairing, confirms the sender really is in the corridor paired with the
 * target's, and measures reach through the mirror before anything happens. See
 * {@link PortalPuppetAttack}.</p>
 */
public record PortalPuppetAttackPacket(int sourceEntityId) implements CustomPacketPayload {

    public static final Type<PortalPuppetAttackPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_puppet_attack"));

    public static final StreamCodec<FriendlyByteBuf, PortalPuppetAttackPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.sourceEntityId()),
            buf -> new PortalPuppetAttackPacket(buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalPuppetAttackPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.isDeadOrDying() || player.isSpectator()) return;
            PortalPuppetAttack.attack(player, packet.sourceEntityId());
        });
    }
}
