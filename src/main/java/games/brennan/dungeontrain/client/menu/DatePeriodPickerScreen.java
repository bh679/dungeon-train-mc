package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.template.dateperiod.DatePeriod;
import games.brennan.dungeontrain.template.dateperiod.DatePeriods;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The Dates picker behind a template's data-sheet {@code Dates} line: one stay-open tick-box per
 * festival, grouped by months, each showing its next window. Ticking locks the template to those
 * periods (it spawns only while one is on); "Any time" clears them all.
 *
 * <p>Multi-select like the Stage picker's group-member mode: the working set flips locally so the
 * box updates on the next rebuild, and each flip dispatches {@code … period <addr> <id> on|off}.</p>
 */
public final class DatePeriodPickerScreen implements MenuScreen {

    private static final String LANG = "gui.dungeontrain.date_period.";

    private final String commandRoot;
    private final String templateLabel;
    private final TreeSet<String> selected = new TreeSet<>();

    /**
     * @param commandRoot the {@code … period <addr>} prefix, from {@link PlotCategory#command}
     * @param current     the template's periods as the server last sent them
     */
    private DatePeriodPickerScreen(String commandRoot, String templateLabel, Collection<String> current) {
        this.commandRoot = commandRoot;
        this.templateLabel = templateLabel == null ? "" : templateLabel;
        if (current != null) selected.addAll(current);
    }

    /** The picker for a template row, or empty when its category has no date gate. */
    public static Optional<DatePeriodPickerScreen> forTemplate(PlotCategory category, String modelId, String modelName,
                                                               String templateLabel, Collection<String> current) {
        if (category == null) return Optional.empty();
        return category.command(PlotCategory.Verb.PERIOD, modelId, modelName)
            .map(root -> new DatePeriodPickerScreen(root, templateLabel, current));
    }

    /** The translated name of period {@code id}; the id itself when this build has no name for it. */
    public static String periodName(String id) {
        String key = LANG + id;
        String name = Component.translatable(key).getString();
        return name.equals(key) ? id : name;
    }

    /** {@code ids} as translated names, comma-joined, in picker order. */
    public static String periodNames(Collection<String> ids) {
        List<String> names = new ArrayList<>();
        for (DatePeriod p : DatePeriods.all()) {
            if (ids.contains(p.id())) names.add(periodName(p.id()));
        }
        for (String id : ids) {
            if (!DatePeriods.exists(id)) names.add(id);
        }
        return String.join(", ", names);
    }

    @Override
    public String title() {
        return MenuLang.t("dates.title", templateLabel);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.ClientAction(checkbox(selected.isEmpty()) + MenuLang.t("dates.any_time"), () -> {
            selected.clear();
            CommandRunner.run(commandRoot + " clear");
        }, false));

        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM", clientLocale());
        for (String group : DatePeriods.MONTH_GROUPS) {
            out.add(new CommandMenuEntry.Label(MenuLang.t("dates.group." + group)));
            for (DatePeriod p : DatePeriods.all()) {
                if (!p.months().equals(group)) continue;
                String id = p.id();
                boolean on = selected.contains(id);
                String label = checkbox(on) + MenuLang.t("dates.row", periodName(id), windowText(p, today, fmt));
                out.add(new CommandMenuEntry.ClientAction(label, () -> {
                    boolean nowOn = !selected.remove(id);
                    if (nowOn) selected.add(id);
                    CommandRunner.run(commandRoot + " " + id + (nowOn ? " on" : " off"));
                }, on));
            }
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.done")));
        return out;
    }

    private static String windowText(DatePeriod p, LocalDate today, DateTimeFormatter fmt) {
        return p.nextFrom(today)
            .map(w -> w.from().equals(w.to())
                ? fmt.format(w.from())
                : MenuLang.t("dates.window", fmt.format(w.from()), fmt.format(w.to())))
            .orElseGet(() -> MenuLang.t("dates.no_date"));
    }

    /** The game's language as a {@link Locale}, so month names follow it ({@code de_de} → {@code de-DE}). */
    private static Locale clientLocale() {
        try {
            String code = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
            return Locale.forLanguageTag(code.replace('_', '-'));
        } catch (RuntimeException e) {
            return Locale.ROOT;
        }
    }

    private static String checkbox(boolean on) {
        return on ? "[x] " : "[ ] ";
    }
}
