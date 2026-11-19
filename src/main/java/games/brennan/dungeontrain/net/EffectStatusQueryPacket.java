package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Client → server: these effects have sat at 00:00 on my screen; do you still have them on me?
 *
 * <p>Sent by {@code client/StuckEffectWatcher}. For each effect the server re-sends its real state: the
 * effect with its true time left if the player has it, or a remove packet if not, which clears a ghost
 * the client gave itself (BetterNether's Naga shot did this before {@code BetterNetherNagaClientEffectMixin}).
 * Unknown ids are dropped, the list is capped at {@link #MAX_EFFECTS}, and one player is answered at most
 * once a second.</p>
 */
public record EffectStatusQueryPacket(List<ResourceLocation> effects) implements CustomPacketPayload {

    public static final int MAX_EFFECTS = 32;
    private static final int MIN_INTERVAL_TICKS = 20;
    /** Server thread only (handled in {@code enqueueWork}). */
    private static final Map<ServerPlayer, Integer> LAST_ANSWERED = new WeakHashMap<>();

    public static final Type<EffectStatusQueryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "effect_status_query"));

    public static final StreamCodec<FriendlyByteBuf, EffectStatusQueryPacket> STREAM_CODEC =
        StreamCodec.of(EffectStatusQueryPacket::encode, EffectStatusQueryPacket::decode);

    public EffectStatusQueryPacket {
        effects = List.copyOf(effects.size() > MAX_EFFECTS ? effects.subList(0, MAX_EFFECTS) : effects);
    }

    private static void encode(FriendlyByteBuf buf, EffectStatusQueryPacket p) {
        buf.writeVarInt(p.effects.size());
        p.effects.forEach(buf::writeResourceLocation);
    }

    private static EffectStatusQueryPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_EFFECTS) {
            throw new IllegalArgumentException("effect_status_query: " + count + " effects (max " + MAX_EFFECTS + ")");
        }
        List<ResourceLocation> effects = new ArrayList<>(count);
        for (int i = 0; i < count; i++) effects.add(buf.readResourceLocation());
        return new EffectStatusQueryPacket(effects);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EffectStatusQueryPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) answer(player, packet.effects());
        });
    }

    private static void answer(ServerPlayer player, List<ResourceLocation> effects) {
        int now = player.server.getTickCount();
        Integer last = LAST_ANSWERED.get(player);
        if (last != null && now - last < MIN_INTERVAL_TICKS) return;
        LAST_ANSWERED.put(player, now);
        for (ResourceLocation id : effects) {
            Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.getHolder(id);
            if (effect.isEmpty()) continue;
            MobEffectInstance held = player.getEffect(effect.get());
            player.connection.send(held != null
                ? new ClientboundUpdateMobEffectPacket(player.getId(), held, false)
                : new ClientboundRemoveMobEffectPacket(player.getId(), effect.get()));
        }
    }
}
