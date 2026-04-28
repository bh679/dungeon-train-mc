package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: snapshot of the variant + contents registries at login
 * time. The client uses this to populate the left-side prefab tabs without
 * needing per-server-world registry access on the client side.
 *
 * <p>Sent once per {@code PlayerLoggedInEvent}. Mid-session registry changes
 * (e.g. an OP drops a new {@code .nbt} file into {@code config/dungeontrain/}
 * and {@code reload}s) won't reach already-connected clients until they
 * reconnect — acceptable for v1.</p>
 *
 * <p>Payload is two ordered lists of ids: variant ids first, then contents
 * ids. Both lists are short (4 built-ins + a handful of customs typically).</p>
 */
public record PrefabRegistrySyncPacket(List<String> variantIds, List<String> contentsIds) {

    public static PrefabRegistrySyncPacket fromRegistries() {
        List<String> variants = new ArrayList<>(BlockVariantPrefabStore.allIds());
        List<String> loot = new ArrayList<>(LootPrefabStore.allIds());
        return new PrefabRegistrySyncPacket(variants, loot);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(variantIds.size());
        for (String id : variantIds) buf.writeUtf(id, 64);
        buf.writeVarInt(contentsIds.size());
        for (String id : contentsIds) buf.writeUtf(id, 64);
    }

    public static PrefabRegistrySyncPacket decode(FriendlyByteBuf buf) {
        int vCount = buf.readVarInt();
        List<String> variants = new ArrayList<>(vCount);
        for (int i = 0; i < vCount; i++) variants.add(buf.readUtf(64));
        int cCount = buf.readVarInt();
        List<String> contents = new ArrayList<>(cCount);
        for (int i = 0; i < cCount; i++) contents.add(buf.readUtf(64));
        return new PrefabRegistrySyncPacket(variants, contents);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> PrefabTabState.applyRegistry(variantIds, contentsIds)));
        ctx.setPacketHandled(true);
    }
}
