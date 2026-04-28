package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.menu.PrefabTabState;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.ContainerContentsEntry;
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
 * Each entry carries:
 *
 * <ul>
 *   <li>{@code id} — the prefab name (used as the NBT discriminator value).</li>
 *   <li>{@code iconBlockId} — the block id rendered as the stack's main icon
 *       in the creative tab grid.</li>
 *   <li>{@code committed} — whether the prefab exists in the source tree or
 *       the bundled jar (vs. config-dir only). Drives the uncommitted-slot
 *       tint mixin.</li>
 *   <li>{@code blockIds} (variants) / {@code items} (loot) — the full
 *       constituent list, used by the tooltip layer to render an icon-grid
 *       preview when the user hovers a prefab. Tooltip rendering is per-frame
 *       on the client and cannot block on a server round-trip, so the data
 *       is shipped up front.</li>
 * </ul>
 *
 * <p>Sent on {@code PlayerLoggedInEvent} and re-broadcast after any
 * {@link SaveBlockVariantPrefabPacket}/{@link SaveLootPrefabPacket} so
 * connected players see new entries (and updated icon-grid contents)
 * immediately.</p>
 */
public record PrefabRegistrySyncPacket(List<VariantEntry> variants, List<LootEntry> loot) {

    /** Variant prefab tab entry — full block list used by the icon-grid tooltip. */
    public record VariantEntry(String id, String iconBlockId, boolean committed, List<String> blockIds) {
        public VariantEntry {
            blockIds = List.copyOf(blockIds);
        }
    }

    /** Loot prefab tab entry — full pool list used by the icon-grid tooltip. */
    public record LootEntry(String id, String iconBlockId, boolean committed, List<LootItem> items) {
        public LootEntry {
            items = List.copyOf(items);
        }
    }

    /** One entry in a loot prefab — flattened to (itemId, count) for wire stability. */
    public record LootItem(String itemId, int count) {}

    /**
     * Build the payload from the live server-side stores. Each prefab is
     * loaded once: for variants, the icon block is the first state's block
     * and the block list is every state's block id. For loot, the icon block
     * is the saved container and the item list is the pool entries.
     */
    public static PrefabRegistrySyncPacket fromRegistries() {
        List<VariantEntry> variants = new ArrayList<>();
        for (String id : BlockVariantPrefabStore.allIds()) {
            Optional<List<VariantState>> loaded = BlockVariantPrefabStore.load(id);
            if (loaded.isEmpty() || loaded.get().isEmpty()) continue;
            List<VariantState> states = loaded.get();
            Block firstBlock = states.get(0).state().getBlock();
            ResourceLocation iconId = BuiltInRegistries.BLOCK.getKey(firstBlock);
            List<String> blockIds = new ArrayList<>(states.size());
            for (VariantState s : states) {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(s.state().getBlock());
                blockIds.add(rl.toString());
            }
            variants.add(new VariantEntry(id, iconId.toString(),
                BlockVariantPrefabStore.isCommitted(id), blockIds));
        }
        List<LootEntry> loot = new ArrayList<>();
        for (String id : LootPrefabStore.allIds()) {
            Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(id);
            if (loaded.isEmpty()) continue;
            LootPrefabStore.Data data = loaded.get();
            List<ContainerContentsEntry> entries = data.pool().entries();
            List<LootItem> items = new ArrayList<>(entries.size());
            for (ContainerContentsEntry e : entries) {
                if (e.isAir()) continue;
                items.add(new LootItem(e.itemId().toString(), e.count()));
            }
            loot.add(new LootEntry(data.id(), data.sourceBlock().toString(),
                LootPrefabStore.isCommitted(id), items));
        }
        return new PrefabRegistrySyncPacket(variants, loot);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(variants.size());
        for (VariantEntry e : variants) {
            buf.writeUtf(e.id(), 64);
            buf.writeUtf(e.iconBlockId(), 128);
            buf.writeBoolean(e.committed());
            buf.writeVarInt(e.blockIds().size());
            for (String bid : e.blockIds()) buf.writeUtf(bid, 128);
        }
        buf.writeVarInt(loot.size());
        for (LootEntry e : loot) {
            buf.writeUtf(e.id(), 64);
            buf.writeUtf(e.iconBlockId(), 128);
            buf.writeBoolean(e.committed());
            buf.writeVarInt(e.items().size());
            for (LootItem it : e.items()) {
                buf.writeUtf(it.itemId(), 128);
                buf.writeVarInt(it.count());
            }
        }
    }

    public static PrefabRegistrySyncPacket decode(FriendlyByteBuf buf) {
        int vCount = buf.readVarInt();
        List<VariantEntry> variants = new ArrayList<>(vCount);
        for (int i = 0; i < vCount; i++) {
            String id = buf.readUtf(64);
            String iconBlockId = buf.readUtf(128);
            boolean committed = buf.readBoolean();
            int bCount = buf.readVarInt();
            List<String> blockIds = new ArrayList<>(bCount);
            for (int j = 0; j < bCount; j++) blockIds.add(buf.readUtf(128));
            variants.add(new VariantEntry(id, iconBlockId, committed, blockIds));
        }
        int lCount = buf.readVarInt();
        List<LootEntry> loot = new ArrayList<>(lCount);
        for (int i = 0; i < lCount; i++) {
            String id = buf.readUtf(64);
            String iconBlockId = buf.readUtf(128);
            boolean committed = buf.readBoolean();
            int iCount = buf.readVarInt();
            List<LootItem> items = new ArrayList<>(iCount);
            for (int j = 0; j < iCount; j++) {
                String itemId = buf.readUtf(128);
                int count = buf.readVarInt();
                items.add(new LootItem(itemId, count));
            }
            loot.add(new LootEntry(id, iconBlockId, committed, items));
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
