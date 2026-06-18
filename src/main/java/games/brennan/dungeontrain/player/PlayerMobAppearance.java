package games.brennan.dungeontrain.player;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Immutable snapshot of a PlayerMob's visual identity for the death-screen
 * portrait: skin (bundled index, optional URL / "echo" {@code playermob-profile}
 * ref, slim flag), display name, and the six equipment stacks it was holding /
 * wearing.
 *
 * <p>Captured server-side at the moment a mob is befriended or killed (the live
 * entity is loaded then — by death-screen time it may be unloaded), carried in
 * {@link games.brennan.dungeontrain.net.DeathStatsPacket}, and replayed onto a
 * throwaway client-side {@code PlayerMobEntity} so the death screen can render
 * an animated portrait. The bundled {@code PlayerMobRenderer} resolves any of
 * the three skin variants from the skin fields automatically, and its armor /
 * held-item layers render the equipment.</p>
 *
 * <p>The hard reference to {@link PlayerMobEntity} is safe — playermob is always
 * jarJar'd into Dungeon Train (the same guarantee {@code compat.EchoIdentity}
 * relies on).</p>
 */
public record PlayerMobAppearance(
        int skinIndex,
        String skinTextureUrl,
        boolean slim,
        String name,
        ItemStack mainHand,
        ItemStack offHand,
        ItemStack head,
        ItemStack chest,
        ItemStack legs,
        ItemStack feet) {

    /**
     * Snapshot a live PlayerMob's skin fields, display name, and equipment. A
     * {@code null} skin URL is normalized to {@code ""}; equipment stacks are
     * copied so later mutation of the live mob can't change the snapshot.
     */
    public static PlayerMobAppearance capture(PlayerMobEntity mob) {
        String url = mob.getSkinTextureUrl();
        return new PlayerMobAppearance(
                mob.getSkinIndex(),
                url == null ? "" : url,
                mob.isSkinSlim(),
                mob.getName().getString(),
                mob.getItemBySlot(EquipmentSlot.MAINHAND).copy(),
                mob.getItemBySlot(EquipmentSlot.OFFHAND).copy(),
                mob.getItemBySlot(EquipmentSlot.HEAD).copy(),
                mob.getItemBySlot(EquipmentSlot.CHEST).copy(),
                mob.getItemBySlot(EquipmentSlot.LEGS).copy(),
                mob.getItemBySlot(EquipmentSlot.FEET).copy());
    }

    /** Write this appearance to the death packet (see {@link #decode}). */
    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(skinIndex);
        buf.writeUtf(skinTextureUrl);
        buf.writeBoolean(slim);
        buf.writeUtf(name);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, mainHand);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offHand);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, head);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, chest);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, legs);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, feet);
    }

    /** Read an appearance written by {@link #encode}. */
    public static PlayerMobAppearance decode(RegistryFriendlyByteBuf buf) {
        int skinIndex = buf.readVarInt();
        String url = buf.readUtf();
        boolean slim = buf.readBoolean();
        String name = buf.readUtf();
        ItemStack mainHand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack offHand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack head = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack chest = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack legs = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack feet = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        return new PlayerMobAppearance(skinIndex, url, slim, name, mainHand, offHand, head, chest, legs, feet);
    }
}
