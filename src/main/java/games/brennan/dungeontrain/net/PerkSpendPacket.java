package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkActionGuard;
import games.brennan.dungeontrain.perks.PerkBalances;
import games.brennan.dungeontrain.perks.PerkDef;
import games.brennan.dungeontrain.perks.PerkPoints;
import games.brennan.dungeontrain.perks.PerkRankCost;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkState;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: put one active point into an unlocked perk. Checked like
 * {@link PerkUnlockPacket}: known, unlocked, below its max rank, and an active point in the balance.
 */
public record PerkSpendPacket(String perkId) implements CustomPacketPayload {

    public static final Type<PerkSpendPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_spend"));

    public static final StreamCodec<FriendlyByteBuf, PerkSpendPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.perkId == null ? "" : p.perkId, PerkUnlockPacket.MAX_ID_CHARS),
            buf -> new PerkSpendPacket(buf.readUtf(PerkUnlockPacket.MAX_ID_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PerkSpendPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            String id = packet.perkId;
            if (id == null || id.isEmpty() || !PerkActionGuard.allow(player, id)) return;
            String refusal = refusal(player, id);
            if (refusal != null) {
                DungeonTrainNet.sendTo(player, new PerkRefusedPacket(id, refusal));
                Perks.sync(player);
                PerkBalances.sync(player);
                return;
            }
            if (!PerkPoints.spendActive(player, PerkRankCost.nextCost(Perks.state(player)))) return;
            Perks.addActive(player, id, 1);
            PerkBalances.sync(player);
        });
    }

    static String refusal(ServerPlayer player, String id) {
        if (PerkTreeLoader.get().perk(id).isEmpty()) return "gui.dungeontrain.perks.refused.unknown";
        PerkRegistry.bootstrap();
        PerkDef def = PerkRegistry.get(id).orElse(null);
        if (def == null || !PerkRegistry.isBuilt(id)) return "gui.dungeontrain.perks.refused.not_built";
        PerkState state = Perks.state(player);
        if (!state.isUnlocked(id)) return "gui.dungeontrain.perks.refused.locked";
        if (state.activePoints(id) >= state.unlockLevel(id)) return "gui.dungeontrain.perks.refused.full";
        if (PerkPoints.active(player) < PerkRankCost.nextCost(state)) return "gui.dungeontrain.perks.refused.no_active";
        return null;
    }
}
