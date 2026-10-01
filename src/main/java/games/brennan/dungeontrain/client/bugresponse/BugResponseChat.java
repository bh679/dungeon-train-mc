package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.bugresponse.BugResponse.Fix;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Kind;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Result;
import games.brennan.dungeontrain.client.version.compare.ChangelogTag;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The bug-report response as chat lines, for reports sent from the on-demand survey ({@code /bug},
 * {@code /feedback}) where there is no death-screen card to show it on. Says what
 * {@link BugResponseCard} says, from the same {@link BugResponse} result and the same lang keys; the
 * card's buttons become clickable links: Update and Get open the modpack page, See changes and the lag
 * tips run {@link #COMMAND}, which opens the screen on the client.
 *
 * <p>Pure: the caller hands in the locale (for plural forms) and the applicable tips as data, so the
 * lines can be built and checked without a running client.</p>
 */
public final class BugResponseChat {

    private static final String KEY = "gui.dungeontrain.bug_response.";
    static final String PREFIX_KEY = "chat.dungeontrain.bug_response.prefix";
    static final String UPDATE_KEY = "gui.dungeontrain.death.update_button";

    /** The client command the links run; registered by {@link BugResponseChatNotifier}. */
    public static final String COMMAND = "dt-bug-response";
    static final String CHANGES_COMMAND = "/" + COMMAND + " changes";

    /** One lag tip as chat needs it. {@code button} null: nothing to click; {@code hint} is shown instead. */
    public record TipLine(String id, Component text, @Nullable Component button, @Nullable Component hint) {}

    private BugResponseChat() {}

    static String tipCommand(String tipId) {
        return "/" + COMMAND + " tip " + tipId;
    }

    /** The tips as chat data; a tip is clickable when the card would give it a button. */
    public static List<TipLine> tipLines(List<LagTips.Tip> tips) {
        List<TipLine> out = new ArrayList<>();
        for (LagTips.Tip t : tips) {
            boolean clickable = t.button() != null && t.action() != null;
            out.add(new TipLine(t.id(), t.text(), clickable ? t.button() : null, t.hint()));
        }
        return List.copyOf(out);
    }

    /**
     * The response in two sends: {@code now} (headline: title and what it means) and {@code later}
     * (the details: fixes, update lines, links), posted {@link BugResponseChatNotifier#LATER_DELAY_TICKS}
     * after, so the headline is read before the details push it up the chat. A short response arrives
     * whole: {@code later} is empty.
     */
    public record Messages(List<Component> now, List<Component> later) {}

    /** A response longer than this many chat messages is sent in two parts. */
    static final int SPLIT_ABOVE = 6;

    /** The lag card's title: the tips heading, or a plain thanks when no tip applies. */
    static String lagTitleKey(boolean hasTips) {
        return KEY + (hasTips ? "tips.title" : "sent");
    }

    /** The response, one chat message per element, the first carrying the {@code [Dungeon Train]} prefix. */
    public static List<Component> lines(Result r, List<TipLine> tips, @Nullable String locale) {
        Messages m = messages(r, tips, locale);
        List<Component> out = new ArrayList<>(m.now());
        out.addAll(m.later());
        return List.copyOf(out);
    }

    /** {@link #lines}, split into what to send now and what to send after a pause. */
    public static Messages messages(Result r, List<TipLine> tips, @Nullable String locale) {
        List<Component> head = new ArrayList<>();
        List<Component> tail = new ArrayList<>();
        switch (r.kind()) {
            case FIXED -> fixed(r, head, tail);
            case MULTIPLAYER -> multiplayer(r, locale, head, tail);
            case LAG_TIPS -> lag(r, tips, locale, head, tail);
            case GENERIC -> generic(r, locale, head, tail);
        }
        links(r).ifPresent(tail::add);

        head.set(0, Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.GOLD)
                .append(" ").append(head.get(0)));
        if (head.size() + tail.size() <= SPLIT_ABOVE) {
            head.addAll(tail);
            return new Messages(List.copyOf(head), List.of());
        }
        return new Messages(List.copyOf(head), List.copyOf(tail));
    }

    // ---- Bodies (mirroring BugResponseCard): headline into head, details into tail ----

    private static void fixed(Result r, List<Component> head, List<Component> tail) {
        String issueId = r.issue().ledgerId().orElse("lag");
        head.add(Component.translatable(KEY + "fixed.title." + issueId).withStyle(ChatFormatting.GREEN));
        head.add(Component.translatable(KEY + "fixed.intro." + issueId, installedText(r)).withStyle(ChatFormatting.GRAY));
        for (Fix fix : r.fixes()) tail.add(fixRow(fix));
        tail.add(Component.translatable(KEY + "fixed.update").withStyle(ChatFormatting.GRAY));
        if (r.curseforgeReview()) {
            tail.add(Component.translatable(KEY + "fixed.curseforge_review").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void multiplayer(Result r, @Nullable String locale, List<Component> head, List<Component> tail) {
        head.add(Component.translatable(KEY + "mp.title").withStyle(ChatFormatting.AQUA));
        head.add(Component.translatable(KEY + "mp.body").withStyle(ChatFormatting.GRAY));
        head.add(Component.translatable(KEY + "mp.sent").withStyle(ChatFormatting.GRAY));
        if (r.behind()) tail.add(outdatedLine(r, locale, Component.translatable(KEY + "outdated.server")));
    }

    private static void lag(Result r, List<TipLine> tips, @Nullable String locale,
                            List<Component> head, List<Component> tail) {
        head.add(Component.translatable(lagTitleKey(!tips.isEmpty())).withStyle(ChatFormatting.GOLD));
        for (TipLine tip : tips) head.add(tipRow(tip));
        if (r.behind()) tail.add(outdatedLine(r, locale, Component.translatable(KEY + "outdated.update")));
    }

    private static void generic(Result r, @Nullable String locale, List<Component> head, List<Component> tail) {
        head.add(Component.translatable(KEY + "sent").withStyle(ChatFormatting.GOLD));
        if (!r.behind()) return;
        Component more = r.bugFixesMissed() > 0
                ? Component.translatable(KEY + "outdated.fixes." + plural(locale, r.bugFixesMissed()), r.bugFixesMissed())
                : Component.translatable(KEY + "outdated.update");
        tail.add(outdatedLine(r, locale, more));
    }

    // ---- Rows ----

    /** "• v0.1080.0: Title [Performance] [Modrinth]". */
    private static Component fixRow(Fix fix) {
        MutableComponent row = Component.literal("• v" + fix.entry().releasedIn() + ": " + fix.entry().title())
                .withStyle(ChatFormatting.GRAY);
        if (fix.entry().tags().contains(ChangelogTag.PERFORMANCE)) {
            row.append(" ").append(chip(ChangelogTag.PERFORMANCE.label(), ChatFormatting.DARK_GREEN));
        } else if (fix.entry().tags().contains(ChangelogTag.FIX)) {
            row.append(" ").append(chip(ChangelogTag.FIX.label(), ChatFormatting.DARK_GREEN));
        }
        if (fix.modrinthOnly()) {
            row.append(" ").append(chip(Component.translatable(KEY + "fixed.tag.modrinth_only"), ChatFormatting.GREEN));
        }
        return row;
    }

    private static Component tipRow(TipLine tip) {
        MutableComponent row = Component.literal("• ").append(tip.text()).withStyle(ChatFormatting.GRAY);
        if (tip.button() != null) {
            row.append(" ").append(commandLink(tip.button(), tipCommand(tip.id())));
        } else if (tip.hint() != null) {
            row.append(" ").append(tip.hint().copy().withStyle(ChatFormatting.DARK_GRAY));
        }
        return row;
    }

    private static Component outdatedLine(Result r, @Nullable String locale, Component tail) {
        String to = r.updateTarget().map(FullSemver::toString).orElse("?");
        return Component.translatable(KEY + "outdated." + plural(locale, r.releasesBehind()),
                        r.releasesBehind(), installedText(r), to)
                .append(" ").append(tail).withStyle(ChatFormatting.YELLOW);
    }

    /** Update / Get on Modrinth / See changes, on one line; empty when none apply. */
    private static Optional<Component> links(Result r) {
        List<Component> links = new ArrayList<>();
        if (r.behind() && r.updateTarget().isPresent()) {
            links.add(urlLink(Component.translatable(UPDATE_KEY, r.updateTarget().get().toString()),
                    BugResponseCard.packUrl(r.launcher())));
        }
        if (r.curseforgeReview() && r.modrinthTarget().isPresent()) {
            links.add(urlLink(Component.translatable(KEY + "button.get", r.modrinthTarget().get().toString(),
                    Platform.MODRINTH.displayName()), BugResponseCard.packUrl(Platform.MODRINTH)));
        }
        if (r.behind() || r.kind() == Kind.FIXED) {
            links.add(commandLink(Component.translatable(KEY + "button.changes"), CHANGES_COMMAND));
        }
        if (links.isEmpty()) return Optional.empty();
        MutableComponent row = Component.empty();
        for (int i = 0; i < links.size(); i++) {
            if (i > 0) row.append(" ");
            row.append(links.get(i));
        }
        return Optional.of(row);
    }

    // ---- Helpers ----

    private static Component chip(Component label, ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(color);
    }

    private static Component urlLink(Component label, String url) {
        return Component.literal("[").append(label).append("]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }

    private static Component commandLink(Component label, String command) {
        return Component.literal("[").append(label).append("]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static String installedText(Result r) {
        return r.installed().map(FullSemver::toString).orElse("?");
    }

    private static String plural(@Nullable String locale, long n) {
        return PluralRules.category(locale, n);
    }
}
