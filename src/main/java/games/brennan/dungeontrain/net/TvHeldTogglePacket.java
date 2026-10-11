package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player left-clicked with a Vista TV in {@code hand} and wants it switched
 * on or off ({@code client/live/HeldTvToggleClient}).
 *
 * <p>The hand is a claim: the server looks at what that hand really holds and does nothing unless it
 * is a TV ({@link TvItems#isTv}). The flipped stack replaces the held one, so the component syncs to
 * every client that can see the item.</p>
 */
public record TvHeldTogglePacket(InteractionHand hand) implements CustomPacketPayload {

    public static final Type<TvHeldTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tv_held_toggle"));

    public static final StreamCodec<FriendlyByteBuf, TvHeldTogglePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.hand() == InteractionHand.OFF_HAND),
            buf -> new TvHeldTogglePacket(buf.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TvHeldTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.isDeadOrDying() || player.isSpectator()) return;
            ItemStack held = player.getItemInHand(packet.hand());
            if (!TvItems.isTv(held)) return;
            boolean on = !TvItems.isOn(held);
            player.setItemInHand(packet.hand(), TvItems.withOn(held, on));
            SoundEvent click = on ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF;
            player.level().playSound(null, player.blockPosition(), click, SoundSource.PLAYERS, 0.6f, on ? 1.1f : 0.9f);
        });
    }
}
