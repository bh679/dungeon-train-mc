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

    /** The response, one chat message per element, the first carrying the {@code [Dungeon Train]} prefix. */
    public static List<Component> lines(Result r, List<TipLine> tips, @Nullable String locale) {
        List<Component> out = new ArrayList<>();
        switch (r.kind()) {
            case FIXED -> fixed(r, out);
            case MULTIPLAYER -> multiplayer(r, locale, out);
            case LAG_TIPS -> lag(r, tips, locale, out);
            case GENERIC -> generic(r, locale, out);
        }
        links(r).ifPresent(out::add);

        Component first = Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.GOLD)
                .append(" ").append(out.get(0));
        out.set(0, first);
        return List.copyOf(out);
    }

    // ---- Bodies (mirroring BugResponseCard) ----

    private static void fixed(Result r, List<Component> out) {
        String issueId = r.issue().ledgerId().orElse("lag");
        out.add(Component.translatable(KEY + "fixed.title." + issueId).withStyle(ChatFormatting.GREEN));
        out.add(Component.translatable(KEY + "fixed.intro." + issueId, installedText(r)).withStyle(ChatFormatting.GRAY));
        for (Fix fix : r.fixes()) out.add(fixRow(fix));
        out.add(Component.translatable(KEY + "fixed.update").withStyle(ChatFormatting.GRAY));
        if (r.curseforgeReview()) {
            out.add(Component.translatable(KEY + "fixed.curseforge_review").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void multiplayer(Result r, @Nullable String locale, List<Component> out) {
        out.add(Component.translatable(KEY + "mp.title").withStyle(ChatFormatting.AQUA));
        out.add(Component.translatable(KEY + "mp.body").withStyle(ChatFormatting.GRAY));
        out.add(Component.translatable(KEY + "mp.sent").withStyle(ChatFormatting.GRAY));
        if (r.behind()) out.add(outdatedLine(r, locale, Component.translatable(KEY + "outdated.server")));
    }

    private static void lag(Result r, List<TipLine> tips, @Nullable String locale, List<Component> out) {
        out.add(Component.translatable(KEY + "tips.title").withStyle(ChatFormatting.GOLD));
        for (TipLine tip : tips) out.add(tipRow(tip));
        if (r.behind()) out.add(outdatedLine(r, locale, Component.translatable(KEY + "outdated.update")));
    }

    private static void generic(Result r, @Nullable String locale, List<Component> out) {
        out.add(Component.translatable(KEY + "sent").withStyle(ChatFormatting.GOLD));
        if (!r.behind()) return;
        Component tail = r.bugFixesMissed() > 0
                ? Component.translatable(KEY + "outdated.fixes." + plural(locale, r.bugFixesMissed()), r.bugFixesMissed())
                : Component.translatable(KEY + "outdated.update");
        out.add(outdatedLine(r, locale, tail));
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
