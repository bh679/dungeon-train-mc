package games.brennan.dungeontrain.provenance;

import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The lines an item's Shift section shows, top to bottom: who and how, the day, the vault number, the
 * carriage id. Side-neutral; {@code client.ProvenanceTooltip} styles and places them.
 */
public final class ProvenanceLines {

    static final String KEY_PREFIX = "tooltip.dungeontrain.provenance.";
    /** Reused from the vault find's old description line, already in every locale. */
    static final String KEY_SERIAL = "lore.dungeontrain.vault_find.serial";

    private ProvenanceLines() {}

    /**
     * @param view       the item's provenance, when it has a holder
     * @param name       the holder's name to show: their current one when known, else the stored one
     * @param locale     the viewer's language, for the date
     * @param serial     the vault find number, when the item has one
     * @param legacy     old-style vault lines on an item not yet migrated, shown in place of the rest
     */
    public static List<Component> of(Optional<ItemProvenance.View> view, String name, Locale locale,
                                     OptionalInt serial, List<Component> legacy) {
        List<Component> out = new ArrayList<>();
        if (view.isPresent()) {
            ItemProvenance.View v = view.get();
            Optional<Component> who = whoLine(v, name);
            who.ifPresent(out::add);
            out.add(Component.literal(dateText(v.day(), locale)));
        } else {
            out.addAll(legacy);
        }
        if (legacy.isEmpty() || view.isPresent()) {
            serial.ifPresent(n -> out.add(Component.translatable(KEY_SERIAL, n)));
        }
        view.flatMap(ItemProvenance.View::carriage).ifPresent(id -> out.add(Component.literal(id)));
        return out;
    }

    /** "Found by Steve" and the like; nothing for a craft AdventureItemNames already credited. */
    static Optional<Component> whoLine(ItemProvenance.View v, String name) {
        return switch (v.how()) {
            case FOUND -> Optional.of(Component.translatable(KEY_PREFIX + "found_by", name));
            case MADE -> v.ainCredited() ? Optional.empty()
                : Optional.of(Component.translatable(KEY_PREFIX + "made_by", name));
            case BOUGHT -> Optional.of(Component.translatable(KEY_PREFIX + "bought_by", name));
            case OLD -> Optional.of(Component.translatable(oldKey(v.variant()), name));
        };
    }

    /** The lang key of old-item line {@code variant}; its text may hold several lines split by {@code \n}. */
    static String oldKey(int variant) {
        return KEY_PREFIX + "old." + (Math.floorMod(variant, ItemProvenance.OLD_VARIANTS) + 1);
    }

    /** {@code epochDay} as the viewer's language writes a date, e.g. "11 Nov 2026" or "Nov 11, 2026". */
    static String dateText(long epochDay, Locale locale) {
        return LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    }
}
