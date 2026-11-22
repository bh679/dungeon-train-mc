package games.brennan.dungeontrain.template.dateperiod;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One festival window a template can be locked to ({@code taschristmascarriage → christmas}).
 *
 * <p>Three ways to say when it falls, one per {@link Rule}:</p>
 * <ul>
 *   <li>{@link Fixed}: the same month-days every year. {@code to} before {@code from} wraps the year
 *       end (New Year, Kwanzaa).</li>
 *   <li>{@link EasterRelative}: day offsets from Western Easter Sunday ({@link Easter}).</li>
 *   <li>{@link Table}: lunar and lunisolar festivals, a list of verified main days, each widened
 *       by {@code before}/{@code after} days. A year missing from the table means the period is off
 *       that year. It never guesses.</li>
 * </ul>
 *
 * @param id     stable key written in {@code weights.json} {@code "periods"} lists, and the lang-key suffix
 * @param months the picker group the period is listed under ({@code jan_apr}, {@code may_aug}, {@code sep_dec})
 */
public record DatePeriod(String id, String months, Rule rule) {

    /** How a period's windows are found. */
    public sealed interface Rule permits Fixed, EasterRelative, Table {
        /** Every window whose main day falls in {@code year} (usually one; none when the table lacks the year). */
        List<Window> windowsIn(int year);
    }

    /** An inclusive date range. */
    public record Window(LocalDate from, LocalDate to) {
        public boolean contains(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    public record Fixed(MonthDay from, MonthDay to) implements Rule {
        @Override
        public List<Window> windowsIn(int year) {
            LocalDate start = from.atYear(year);
            LocalDate end = to.atYear(year);
            if (end.isBefore(start)) end = to.atYear(year + 1);
            return List.of(new Window(start, end));
        }
    }

    public record EasterRelative(int fromOffset, int toOffset) implements Rule {
        @Override
        public List<Window> windowsIn(int year) {
            LocalDate sunday = Easter.sunday(year);
            return List.of(new Window(sunday.plusDays(fromOffset), sunday.plusDays(toOffset)));
        }
    }

    /**
     * {@code mainDays}: every known main day, sorted. A list rather than one per year because the
     * Islamic year is shorter than ours: Eid al-Fitr falls twice in 2033.
     */
    public record Table(int before, int after, List<LocalDate> mainDays) implements Rule {
        public Table {
            mainDays = mainDays.stream().sorted().toList();
        }

        @Override
        public List<Window> windowsIn(int year) {
            List<Window> out = new ArrayList<>(1);
            for (LocalDate main : mainDays) {
                if (main.getYear() == year) out.add(new Window(main.minusDays(before), main.plusDays(after)));
            }
            return out;
        }

        /** True when the table knows at least one main day in {@code year}. */
        public boolean covers(int year) {
            for (LocalDate main : mainDays) {
                if (main.getYear() == year) return true;
            }
            return false;
        }
    }

    /**
     * True when {@code day} falls inside this period. Windows keyed on the year either side are also
     * checked, since a window can cross New Year (Kwanzaa, a Hanukkah starting late December).
     */
    public boolean isOn(LocalDate day) {
        for (int year = day.getYear() - 1; year <= day.getYear() + 1; year++) {
            for (Window w : rule.windowsIn(year)) {
                if (w.contains(day)) return true;
            }
        }
        return false;
    }

    /** The next window that ends on or after {@code day}: the one running today, else the next to start. */
    public Optional<Window> nextFrom(LocalDate day) {
        Window best = null;
        for (int year = day.getYear() - 1; year <= day.getYear() + 2; year++) {
            for (Window w : rule.windowsIn(year)) {
                if (w.to().isBefore(day)) continue;
                if (best == null || w.from().isBefore(best.from())) best = w;
            }
        }
        return Optional.ofNullable(best);
    }
}
