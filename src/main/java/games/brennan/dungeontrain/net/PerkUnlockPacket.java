package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkActionGuard;
import games.brennan.dungeontrain.perks.PerkBalances;
import games.brennan.dungeontrain.perks.PerkPoints;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkState;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: unlock a perk with unlock points. The server is the judge: the id must be in the
 * tree and the registry, not yet unlocked, reachable under {@link PerkTree#reachable}, and affordable.
 * A refusal answers with {@link PerkRefusedPacket} plus a fresh state and balance sync so a stale
 * client corrects itself. Free Play buys like everyone else.
 */
public record PerkUnlockPacket(String perkId) implements CustomPacketPayload {

    static final int MAX_ID_CHARS = 64;

    public static final Type<PerkUnlockPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_unlock"));

    public static final StreamCodec<FriendlyByteBuf, PerkUnlockPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.perkId == null ? "" : p.perkId, MAX_ID_CHARS),
            buf -> new PerkUnlockPacket(buf.readUtf(MAX_ID_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PerkUnlockPacket packet, IPayloadContext ctx) {
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
            Perks.unlock(player, id);
            PerkBalances.sync(player);
        });
    }

    /** The refusal lang key, or null when the unlock may go ahead. Package-visible for tests. */
    static String refusal(ServerPlayer player, String id) {
        PerkTree tree = PerkTreeLoader.get();
        PerkTree.Perk perk = tree.perk(id).orElse(null);
        if (perk == null || perk.isRoot()) return "gui.dungeontrain.perks.refused.unknown";
        PerkRegistry.bootstrap();
        if (!PerkRegistry.isKnown(id)) return "gui.dungeontrain.perks.refused.not_built";
        PerkState state = Perks.state(player);
        if (state.isUnlocked(id)) return "gui.dungeontrain.perks.refused.already";
        if (!tree.reachable(id, state)) return "gui.dungeontrain.perks.refused.reach";
        if (PerkPoints.unlockAvailable(player) < perk.unlockCost()) return "gui.dungeontrain.perks.refused.points";
        return null;
    }
}
