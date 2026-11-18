package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.player.PlayerMobAppearance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The echo this death left behind, condensed for the death screen's echo page: its look (skin,
 * name and the six worn/held stacks), its two disposition traits, the backpack it carries, and the
 * armour points its worn gear gives. Read server-side from PlayerMob's death record by
 * {@link EchoSummaryReader} and carried in {@code DeathStatsPacket}.
 *
 * <p>The carried list holds at most {@link #MAX_CARRIED} non-empty stacks — PlayerMob's backpack
 * size — and is defensively copied so the record stays immutable.</p>
 */
public record EchoSummary(
        PlayerMobAppearance appearance,
        int fightFlight,
        int friendliness,
        List<ItemStack> carried,
        int armour) {

    /** PlayerMob's backpack size ({@code PlayerMobEntity.INVENTORY_SIZE}). */
    public static final int MAX_CARRIED = 8;

    public EchoSummary {
        fightFlight = EchoPersonality.clamp(fightFlight);
        friendliness = EchoPersonality.clamp(friendliness);
        carried = List.copyOf(carried.size() > MAX_CARRIED ? carried.subList(0, MAX_CARRIED) : carried);
        armour = Math.max(0, armour);
    }

    /** This echo's personality class. */
    public EchoPersonality.Kind personality() {
        return EchoPersonality.classify(fightFlight, friendliness);
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        appearance.encode(buf);
        buf.writeVarInt(fightFlight);
        buf.writeVarInt(friendliness);
        buf.writeVarInt(carried.size());
        for (ItemStack stack : carried) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
        buf.writeVarInt(armour);
    }

    public static EchoSummary decode(RegistryFriendlyByteBuf buf) {
        PlayerMobAppearance appearance = PlayerMobAppearance.decode(buf);
        int fightFlight = buf.readVarInt();
        int friendliness = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CARRIED) {
            throw new IllegalArgumentException("EchoSummary: bad carried count " + count);
        }
        List<ItemStack> carried = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            carried.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        int armour = buf.readVarInt();
        return new EchoSummary(appearance, fightFlight, friendliness, carried, armour);
    }
}
