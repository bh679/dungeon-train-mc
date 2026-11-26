package games.brennan.dungeontrain.perks.effects.builder;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.registry.ModDataComponents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mark on a stack that Drift Gift Repaid has already paid for: who was paid and how many
 * sixteenths per item. While the payer could simply take the gift back out, the emeralds would be
 * free money, so the marked stack is charged back when its payer picks it up again (menu slot or
 * dropped item) and the mark is cleared. Anyone else picking it up clears it for free: the gift
 * reached someone, which is the point.
 *
 * @param payer the paid player's uuid, dashless (how the relay stores uuids)
 * @param perItem sixteenths paid per item in the stack
 */
public record DriftGiftTag(String payer, int perItem) {

    public static final Codec<DriftGiftTag> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("payer").forGetter(DriftGiftTag::payer),
            Codec.INT.fieldOf("per_item").forGetter(DriftGiftTag::perItem)
    ).apply(i, DriftGiftTag::new));

    public static String dashless(UUID id) {
        return id.toString().replace("-", "");
    }

    public static DriftGiftTag of(UUID payer, int perItem) {
        return new DriftGiftTag(dashless(payer), perItem);
    }

    public boolean paidTo(UUID player) {
        return payer.equals(dashless(player));
    }

    /** Sixteenths to charge back for {@code count} items. */
    public long chargeFor(int count) {
        return (long) perItem * Math.max(0, count);
    }

    @Nullable
    public static DriftGiftTag of(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(ModDataComponents.DRIFT_GIFT_PAID.get());
    }

    public static void apply(ItemStack stack, DriftGiftTag tag) {
        stack.set(ModDataComponents.DRIFT_GIFT_PAID.get(), tag);
    }

    public static void clear(ItemStack stack) {
        if (!stack.isEmpty()) stack.remove(ModDataComponents.DRIFT_GIFT_PAID.get());
    }
}
