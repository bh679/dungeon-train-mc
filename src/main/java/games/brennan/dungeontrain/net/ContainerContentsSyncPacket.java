package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → client: container-contents menu state for one cell.
 *
 * <p>Active packet ({@code localPos != null}): render the menu anchored at
 * {@code anchorPos} with axes {@code anchorRight}/{@code anchorUp},
 * showing {@code entries} as the pool for the targeted container. When
 * {@code linkedPrefabId != null}, the menu also draws a link sub-row that
 * names the linked loot prefab and offers an unlink action. When
 * {@code vaultKey != null} the target is a vault, and the menu draws a Key row
 * naming the item that unlocks it.</p>
 *
 * <p>Inactive packet ({@code localPos == null}): close the menu.</p>
 */
public record ContainerContentsSyncPacket(
    String plotKey,
    @Nullable BlockPos localPos,
    List<Entry> entries,
    int fillMin,
    int fillMax,
    int containerSize,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp,
    @Nullable String linkedPrefabId,
    @Nullable String vaultKey
) implements CustomPacketPayload {

    /** Not a vault: no Key row. */
    public ContainerContentsSyncPacket(String plotKey, @Nullable BlockPos localPos, List<Entry> entries,
                                       int fillMin, int fillMax, int containerSize,
                                       Vec3 anchorPos, Vec3 anchorRight, Vec3 anchorUp,
                                       @Nullable String linkedPrefabId) {
        this(plotKey, localPos, entries, fillMin, fillMax, containerSize, anchorPos, anchorRight, anchorUp,
            linkedPrefabId, null);
    }

    /**
     * Single pool entry on the wire — item registry id, max stack count, weight,
     * plus the per-entry random-durability / random-enchantment master toggles
     * and their 0-100 % chances.
     * {@code count} is a max and {@code minCount} the min — roll-time produces a uniform-random
     * value in {@code [minCount, count]} ({@code minCount} is 1 unless the author raised it).
     * {@code potionId} is the stored vanilla potion id for a potion entry, or {@code ""} when none.
     * {@code scaleWithDistance} is the random-potion placeholder's tier toggle and
     * {@code potionForm} its bottle-form ordinal ({@code PotionForm}); {@code includeLuck} its
     * Luck toggle.
     * {@code bookTitle} is the row's book label: a written-book entry's stored title (the pages
     * stay server-side), or a book entry's enchantments ("mending 1", "1-7 random"); {@code ""}
     * when none. {@code bookEnchMin}..{@code bookEnchMax} is a book entry's random-enchantment
     * range ({@code BookEnchants}); max 0 when it has none. {@code bookLevelMin}..{@code bookLevelMax}
     * is the level range they roll at (0 = top level), and {@code bookEnchList} the builder's on/off
     * list, enchantment id to on, or {@code null} while the pool keeps the default rule.
     * {@code contents} is the loot prefab a shulker box entry is packed from, {@code ""} when none.
     */
    public record Entry(String itemId, int count, int minCount, int weight,
                        boolean randomDurability, int durabilityChance,
                        boolean randomEnchantment, int enchantmentChance,
                        int slotOverride, String potionId, boolean scaleWithDistance,
                        int potionForm, String bookTitle, int bookEnchMin, int bookEnchMax,
                        int bookLevelMin, int bookLevelMax, @Nullable Map<String, Boolean> bookEnchList,
                        boolean includeLuck, String contents) {

        /** Back-compat: an entry packed from no loot prefab. */
        public Entry(String itemId, int count, int minCount, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm, String bookTitle, int bookEnchMin, int bookEnchMax,
                     int bookLevelMin, int bookLevelMax, @Nullable Map<String, Boolean> bookEnchList,
                     boolean includeLuck) {
            this(itemId, count, minCount, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, bookTitle, bookEnchMin, bookEnchMax, bookLevelMin, bookLevelMax, bookEnchList,
                includeLuck, "");
        }

        /** Back-compat: Luck toggle off. */
        public Entry(String itemId, int count, int minCount, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm, String bookTitle, int bookEnchMin, int bookEnchMax,
                     int bookLevelMin, int bookLevelMax, @Nullable Map<String, Boolean> bookEnchList) {
            this(itemId, count, minCount, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, bookTitle, bookEnchMin, bookEnchMax, bookLevelMin, bookLevelMax, bookEnchList, false);
        }

        /** Back-compat: random book enchantments at their top level, default list. */
        public Entry(String itemId, int count, int minCount, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm, String bookTitle, int bookEnchMin, int bookEnchMax) {
            this(itemId, count, minCount, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, bookTitle, bookEnchMin, bookEnchMax, 0, 0, null);
        }

        /** Back-compat: entry with no random book enchantments. */
        public Entry(String itemId, int count, int minCount, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm, String bookTitle) {
            this(itemId, count, minCount, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, bookTitle, 0, 0);
        }

        /** Back-compat: entry with no minimum count (rolls from 1). */
        public Entry(String itemId, int count, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm, String bookTitle) {
            this(itemId, count, 1, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, bookTitle);
        }

        /** Back-compat: entry with no stored book. */
        public Entry(String itemId, int count, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride, String potionId, boolean scaleWithDistance,
                     int potionForm) {
            this(itemId, count, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, potionId, scaleWithDistance,
                potionForm, "");
        }

        /** Back-compat: entry with no stored potion, default scaling. */
        public Entry(String itemId, int count, int weight,
                     boolean randomDurability, int durabilityChance,
                     boolean randomEnchantment, int enchantmentChance,
                     int slotOverride) {
            this(itemId, count, weight, randomDurability, durabilityChance,
                randomEnchantment, enchantmentChance, slotOverride, "", true, 0);
        }

        public boolean hasPotion() {
            return potionId != null && !potionId.isEmpty();
        }

        public boolean hasBook() {
            return bookTitle != null && !bookTitle.isEmpty();
        }

        public boolean hasRandomBookEnchants() {
            return bookEnchMax > 0;
        }
    }

    /** Most enchantments a row's list carries; far above any modpack's count, only a guard on a bad packet. */
    private static final int MAX_BOOK_LIST = 1024;

    public static final Type<ContainerContentsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_contents_sync"));

    public static final StreamCodec<FriendlyByteBuf, ContainerContentsSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerContentsSyncPacket::decode
        );

    public static ContainerContentsSyncPacket empty() {
        return new ContainerContentsSyncPacket(
            "", null, Collections.emptyList(), 0, -1, 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(plotKey, 128);
        if (localPos == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeInt(fillMin);
        buf.writeInt(fillMax);
        buf.writeVarInt(containerSize);
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.itemId(), 256);
            buf.writeVarInt(e.count());
            buf.writeVarInt(e.minCount());
            buf.writeVarInt(e.weight());
            buf.writeBoolean(e.randomDurability());
            buf.writeVarInt(e.durabilityChance());
            buf.writeBoolean(e.randomEnchantment());
            buf.writeVarInt(e.enchantmentChance());
            buf.writeVarInt(e.slotOverride());
            buf.writeUtf(e.potionId() == null ? "" : e.potionId(), 256);
            buf.writeBoolean(e.scaleWithDistance());
            buf.writeByte(e.potionForm());
            buf.writeBoolean(e.includeLuck());
            buf.writeUtf(e.bookTitle() == null ? "" : e.bookTitle(), 64);
            buf.writeVarInt(e.bookEnchMin());
            buf.writeVarInt(e.bookEnchMax());
            buf.writeVarInt(e.bookLevelMin());
            buf.writeVarInt(e.bookLevelMax());
            buf.writeBoolean(e.bookEnchList() != null);
            if (e.bookEnchList() != null) {
                buf.writeVarInt(e.bookEnchList().size());
                e.bookEnchList().forEach((id, on) -> {
                    buf.writeUtf(id, 256);
                    buf.writeBoolean(on);
                });
            }
            buf.writeUtf(e.contents() == null ? "" : e.contents(), 64);
        }
        if (linkedPrefabId == null || linkedPrefabId.isEmpty()) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            buf.writeUtf(linkedPrefabId, 64);
        }
        if (vaultKey == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            buf.writeUtf(vaultKey, 256);
        }
    }

    public static ContainerContentsSyncPacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        boolean active = buf.readBoolean();
        if (!active) {
            return new ContainerContentsSyncPacket(
                key, null, Collections.emptyList(), 0, -1, 0,
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, null);
        }
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int fillMin = buf.readInt();
        int fillMax = buf.readInt();
        int containerSize = buf.readVarInt();
        Vec3 anchor = readVec3(buf);
        Vec3 right = readVec3(buf);
        Vec3 up = readVec3(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(256);
            int count = buf.readVarInt();
            int minCount = buf.readVarInt();
            int weight = buf.readVarInt();
            boolean randDur = buf.readBoolean();
            int durChance = buf.readVarInt();
            boolean randEnch = buf.readBoolean();
            int enchChance = buf.readVarInt();
            int slotOverride = buf.readVarInt();
            String potionId = buf.readUtf(256);
            boolean scale = buf.readBoolean();
            int form = buf.readByte();
            boolean luck = buf.readBoolean();
            String bookTitle = buf.readUtf(64);
            int bookEnchMin = buf.readVarInt();
            int bookEnchMax = buf.readVarInt();
            int bookLevelMin = buf.readVarInt();
            int bookLevelMax = buf.readVarInt();
            Map<String, Boolean> bookEnchList = null;
            if (buf.readBoolean()) {
                int listSize = Math.min(buf.readVarInt(), MAX_BOOK_LIST);
                bookEnchList = new LinkedHashMap<>();
                for (int j = 0; j < listSize; j++) bookEnchList.put(buf.readUtf(256), buf.readBoolean());
            }
            entries.add(new Entry(id, count, minCount, weight, randDur, durChance, randEnch, enchChance, slotOverride, potionId, scale, form, bookTitle,
                bookEnchMin, bookEnchMax, bookLevelMin, bookLevelMax, bookEnchList, luck, buf.readUtf(64)));
        }
        String link = buf.readBoolean() ? buf.readUtf(64) : null;
        String vaultKey = buf.readBoolean() ? buf.readUtf(256) : null;
        return new ContainerContentsSyncPacket(key, local, entries, fillMin, fillMax, containerSize, anchor, right, up, link,
            vaultKey);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerContentsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenu.applySync(packet));
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
