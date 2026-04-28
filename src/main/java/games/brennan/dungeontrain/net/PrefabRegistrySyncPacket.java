package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Server → client: snapshot of the named prefab libraries at login time.
 * Each entry is {@code (id, blockId, committed)} so the client can build a
 * vanilla {@code BlockItem} stack with the right icon for the creative tab,
 * and visually mark "uncommitted" entries (saved without dev mode → only in
 * user config dir, not yet in the source tree / mod jar).
 *
 * <p>Sent on {@code PlayerLoggedInEvent} and re-broadcast after any
 * {@link SaveBlockVariantPrefabPacket}/{@link SaveLootPrefabPacket} so
 * connected players see new entries (and updated commit status) immediately.</p>
 */
public record PrefabRegistrySyncPacket(List<Entry> variants, List<Entry> loot) {

    /**
     * Single tab entry — {@code id} is the prefab name, {@code blockId} the
     * source block to render, {@code committed} whether the prefab exists in
     * the source tree or the bundled jar (vs. config-dir only).
     */
    public record Entry(String id, String blockId, boolean committed) {}

    /**
     * Build the payload from the live server-side stores. Each prefab is
     * loaded once to extract its source-block id; for block variants that's
     * the first state's block, for loot it's the saved container block.
     */
    public static PrefabRegistrySyncPacket fromRegistries() {
        List<Entry> variants = new ArrayList<>();
        for (String id : BlockVariantPrefabStore.allIds()) {
            Optional<List<VariantState>> loaded = BlockVariantPrefabStore.load(id);
            if (loaded.isEmpty() || loaded.get().isEmpty()) continue;
            Block firstBlock = loaded.get().get(0).state().getBlock();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(firstBlock);
            variants.add(new Entry(id, blockId.toString(), BlockVariantPrefabStore.isCommitted(id)));
        }
        List<Entry> loot = new ArrayList<>();
        for (String id : LootPrefabStore.allIds()) {
            Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(id);
            if (loaded.isEmpty()) continue;
            loot.add(new Entry(id, loaded.get().sourceBlock().toString(), LootPrefabStore.isCommitted(id)));
        }
        return new PrefabRegistrySyncPacket(variants, loot);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(variants.size());
        for (Entry e : variants) {
            buf.writeUtf(e.id(), 64);
            buf.writeUtf(e.blockId(), 128);
            buf.writeBoolean(e.committed());
        }
        buf.writeVarInt(loot.size());
        for (Entry e : loot) {
            buf.writeUtf(e.id(), 64);
            buf.writeUtf(e.blockId(), 128);
            buf.writeBoolean(e.committed());
        }
    }

    public static PrefabRegistrySyncPacket decode(FriendlyByteBuf buf) {
        int vCount = buf.readVarInt();
        List<Entry> variants = new ArrayList<>(vCount);
        for (int i = 0; i < vCount; i++) {
            String id = buf.readUtf(64);
            String blockId = buf.readUtf(128);
            boolean committed = buf.readBoolean();
            variants.add(new Entry(id, blockId, committed));
        }
        int lCount = buf.readVarInt();
        List<Entry> loot = new ArrayList<>(lCount);
        for (int i = 0; i < lCount; i++) {
            String id = buf.readUtf(64);
            String blockId = buf.readUtf(128);
            boolean committed = buf.readBoolean();
            loot.add(new Entry(id, blockId, committed));
        }
        return new PrefabRegistrySyncPacket(variants, loot);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> PrefabTabState.applyRegistry(variants, loot)));
        ctx.setPacketHandled(true);
    }
}
