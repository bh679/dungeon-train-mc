package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.ContainerContentsEditPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The C menu's book-enchantment edits (count range, level range, the on/off list) and the nudge it
 * gives a builder when a pool's list has enchantments they have not decided on yet.
 */
public final class BookEnchantsMenuOps {

    private BookEnchantsMenuOps() {}

    /** True for the ops this class answers. */
    public static boolean handles(ContainerContentsEditPacket.Op op) {
        return switch (op) {
            case BUMP_BOOK_ENCH, SET_BOOK_ENCH_RANGE, BUMP_BOOK_ENCH_LEVEL, SET_BOOK_ENCH_LEVEL_RANGE,
                 TOGGLE_BOOK_ENCH, SET_BOOK_ENCH_ALL -> true;
            default -> false;
        };
    }

    /** {@code e} after {@code packet}'s edit; {@code e} itself when it is not a book or nothing changed. */
    public static ContainerContentsEntry apply(ContainerContentsEditPacket packet, ContainerContentsEntry e,
                                               HolderLookup.Provider registries) {
        if (!ContainerContentsEnchants.isBookId(e.itemId())) return e;
        BookEnchants be = e.enchants();
        int min = be == null ? 0 : be.randomMin();
        int max = be == null ? 0 : be.randomMax();
        return switch (packet.op()) {
            case BUMP_BOOK_ENCH -> ContainerContentsEnchants.withRandomRange(e, Math.max(1, min), max + packet.delta());
            case SET_BOOK_ENCH_RANGE -> ContainerContentsEnchants.withRandomRange(e, packet.value2(), packet.delta());
            case BUMP_BOOK_ENCH_LEVEL -> be == null || !be.hasRandom() ? e
                : e.withEnchants(be.withLevels(wrapLevel(be.levelMin() + packet.delta()), be.levelMax()));
            case SET_BOOK_ENCH_LEVEL_RANGE -> be == null || !be.hasRandom() ? e
                : e.withEnchants(be.withLevels(packet.value2(), typedTop(packet.delta())));
            case TOGGLE_BOOK_ENCH -> {
                ResourceLocation id = ResourceLocation.tryParse(packet.itemId());
                yield be == null || !be.hasRandom() || id == null ? e
                    : e.withEnchants(ContainerContentsEnchants.toggled(be, id, candidates(registries)));
            }
            case SET_BOOK_ENCH_ALL -> be == null || !be.hasRandom() ? e
                : e.withEnchants(ContainerContentsEnchants.allSet(be, packet.delta() != 0, candidates(registries)));
            default -> e;
        };
    }

    /** The lowest level, stepped past the top setting, wraps back round to "top level only". */
    private static int wrapLevel(int level) {
        if (level < BookEnchants.LEVEL_TOP) return BookEnchants.MAX_LEVEL_SETTING;
        return level > BookEnchants.MAX_LEVEL_SETTING ? BookEnchants.LEVEL_TOP : level;
    }

    /** A typed highest level at the top setting means "each enchantment's own top level". */
    private static int typedTop(int level) {
        return level >= BookEnchants.MAX_LEVEL_SETTING ? BookEnchants.LEVEL_TOP : level;
    }

    /** The row's list for the sync packet: id to on, or {@code null} while the pool keeps the default rule. */
    public static @Nullable Map<String, Boolean> syncList(@Nullable BookEnchants be) {
        if (be == null || be.list() == null) return null;
        Map<String, Boolean> out = new LinkedHashMap<>();
        be.list().forEach((id, on) -> out.put(id.toString(), on));
        return out;
    }

    /**
     * On opening the menu: when a book row's list is missing enchantments (a mod added since it was
     * saved), say how many on the action bar. The row's List cell shows them too.
     */
    public static void askAboutNew(ServerPlayer player, ContainerContentsPool pool) {
        List<Holder<Enchantment>> candidates = candidates(player.registryAccess());
        int undecided = 0;
        for (ContainerContentsEntry e : pool.entries()) {
            if (e.enchants() != null && e.enchants().hasRandom()) {
                undecided += ContainerContentsEnchants.undecided(e.enchants(), candidates).size();
            }
        }
        if (undecided == 0) return;
        EditorFeedback.actionBar(player,
            Component.translatable("chat.dungeontrain.editor_bar.contents_menu.book_ench_new", undecided),
            ChatFormatting.GOLD);
    }

    private static List<Holder<Enchantment>> candidates(HolderLookup.Provider registries) {
        return registries.lookup(Registries.ENCHANTMENT)
            .map(ContainerContentsEnchants::listCandidates)
            .orElse(List.of());
    }
}
