package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Tells a writer where one of their own unreleased books stands, the first time they pick it up.
 *
 * <p>A book the relay served back to its author unapproved carries its state on the stack
 * ({@link BookModerationTag}), so this needs no relay round-trip and no consent check — the
 * {@code mine=1} fetch that produced the book already made both of those decisions. That also means
 * it works offline, and cannot leave the player holding a mysteriously red book while a request is in
 * flight.</p>
 *
 * <p>Once per stack, via a marker in the same CUSTOM_DATA idiom {@link FamiliarBookGreeter} uses —
 * these two are mutually exclusive by construction (see {@link #maybeGreet}), because a withheld book
 * has no reception in the wild to report and two lines about one pickup is one too many.</p>
 */
public final class UnapprovedBookGreeter {

    /** Per-stack marker: this copy has already said its piece. */
    static final String NBT_GREETED = "dt_unapproved_greeted";

    private UnapprovedBookGreeter() {}

    /**
     * Greet {@code player} about {@code stack} if it is one of their own unreleased books.
     *
     * @return {@code true} when this book is withheld — whether or not a line was actually sent. The
     *         caller uses it to suppress {@link FamiliarBookGreeter}: an unreleased book is by
     *         definition one nobody else has found, so "how it's doing out there" has nothing to say,
     *         and asking the relay for stats on it would be a pointless round-trip besides.
     */
    public static boolean maybeGreet(ServerPlayer player, ItemStack stack) {
        BookModerationState state = BookModerationTag.read(stack);
        if (!state.isWithheld()) return false;
        if (player == null || isGreeted(stack)) return true;
        markGreeted(stack);
        Component line = UnapprovedBookMessage.random(state, player.getRandom());
        if (line != null) player.sendSystemMessage(line);
        return true;
    }

    private static boolean isGreeted(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_GREETED, Tag.TAG_BYTE) && tag.getBoolean(NBT_GREETED);
    }

    private static void markGreeted(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_GREETED, true));
    }
}
