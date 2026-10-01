package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Fix;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Kind;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Result;
import games.brennan.dungeontrain.client.version.compare.ChangelogTag;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.InstalledVersion;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.VersionCompareScreen;
import games.brennan.dungeontrain.client.version.compare.VersionCompareState;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * The card the death screen shows on the bug-report page right after a bug is submitted: "already
 * fixed, update", the known multiplayer issue, lag tips, or a plain thanks, each with an "N releases
 * behind" line when the player is out of date. {@link BugResponse} decides what goes on it; this only
 * lays it out and handles its buttons.
 *
 * <p>The card is re-decided every frame from {@link VersionCompareState}, so listings that land after
 * the report (the fetch starts when the death screen opens) fill it in without a rebuild. It borrows
 * the death screen's bevel / chip / fade look through {@link Chrome}, so it matches the page it sits
 * on and fades with it.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BugResponseCard {

    private static final String KEY = "gui.dungeontrain.bug_response.";

    /** Modpack listing pages — what the Versions page compares, and what the player updates. */
    static final String MODRINTH_PACK_URL = "https://modrinth.com/modpack/bEFyz3ji";
    static final String CURSEFORGE_PACK_URL = "https://www.curseforge.com/projects/1556213";

    private static final int PAD = 8;
    private static final int LINE = 11;
    private static final int BTN_H = 16;
    private static final int TIP_BTN_H = 13;

    private static final int CARD_BG = 0xB0000000;
    private static final int CARD_BORDER = 0xFF5A5236;
    private static final int TITLE_OK = 0xFF8FD18F;
    private static final int TITLE_INFO = 0xFF8FB6E6;
    private static final int TITLE_PLAIN = 0xFFE0B56A;
    private static final int BODY = 0xFFC7BDA7;
    private static final int WARN = 0xFFE9B04F;
    private static final int HINT = 0xFF948A70;
    private static final int TAG_BORDER = 0xFF4E6E53;
    private static final int TAG_TEXT = 0xFF7FAE84;
    private static final int MR_BORDER = 0xFF2FB36F;
    private static final int MR_TEXT = 0xFF5FD59A;

    /** The look the card borrows from its host screen; every colour goes through the host's fade. */
    public interface Chrome {
        int fade(int argb);

        void bevel(GuiGraphics g, int x, int y, int w, int h, Component text, Style style);

        void chip(GuiGraphics g, int x, int y, Component text, int border, int textColor);

        void border(GuiGraphics g, int x, int y, int w, int h, int color);
    }

    /** Button colour sets, matching the death screen's palette. */
    public enum Style { PLAIN, UPDATE, MODRINTH }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean has(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen host;
    private final BugIssue issue;
    private final boolean multiplayer;
    private final List<Hit> hits = new ArrayList<>();

    public BugResponseCard(Screen host, BugIssue issue, boolean multiplayer) {
        this.host = host;
        this.issue = issue;
        this.multiplayer = multiplayer;
        VersionCompareState.ensureFetched();
    }

    public BugIssue issue() {
        return issue;
    }

    /** The response with whatever version data has arrived so far. */
    public Result result() {
        return decideNow(issue, multiplayer);
    }

    /** {@link BugResponse#decide} for this client right now; shared with the chat response. */
    public static Result decideNow(BugIssue issue, boolean multiplayer) {
        return BugResponse.decide(new BugResponse.Input(issue, multiplayer, InstalledVersion.get(),
                Platform.current(), VersionCompareState.versions(Platform.MODRINTH),
                VersionCompareState.versions(Platform.CURSEFORGE), VersionCompareState.ledger()));
    }

    /** Draws the card {@code w} wide, centred on {@code cx}, from {@code y}; returns the y below it. */
    public int render(GuiGraphics g, Font font, Chrome chrome, int cx, int w, int y) {
        hits.clear();
        Result r = result();
        int left = cx - w / 2;
        int inner = w - PAD * 2;

        // Lay out into a list of draw steps first so the background can be sized to fit.
        List<Step> steps = new ArrayList<>();
        switch (r.kind()) {
            case FIXED -> fixedSteps(r, font, inner, steps);
            case MULTIPLAYER -> multiplayerSteps(r, font, inner, steps);
            case LAG_TIPS -> lagSteps(r, font, inner, steps);
            case GENERIC -> genericSteps(r, font, inner, steps);
        }
        buttonSteps(r, font, inner, steps);

        int h = PAD * 2 - 2;
        for (Step s : steps) h += s.height();
        g.fill(left, y, left + w, y + h, chrome.fade(CARD_BG));
        chrome.border(g, left, y, w, h, CARD_BORDER);

        int sy = y + PAD;
        for (Step s : steps) {
            s.draw(g, font, chrome, left + PAD, sy, inner);
            sy += s.height();
        }
        return y + h;
    }

    /** Routes a click to the card's buttons; true when one took it. */
    public boolean click(double mx, double my) {
        for (Hit hit : hits) {
            if (hit.has(mx, my)) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    // ---- Card bodies ----

    private void fixedSteps(Result r, Font font, int inner, List<Step> steps) {
        String issueId = r.issue().ledgerId().orElse("lag");
        text(steps, font, inner, Component.translatable(KEY + "fixed.title." + issueId), TITLE_OK);
        text(steps, font, inner, Component.translatable(KEY + "fixed.intro." + issueId, installedText(r)), BODY);
        for (Fix fix : r.fixes()) steps.add(new FixRow(fix));
        text(steps, font, inner, Component.translatable(KEY + "fixed.update"), BODY);
        if (r.curseforgeReview()) {
            text(steps, font, inner, Component.translatable(KEY + "fixed.curseforge_review"), WARN);
        }
    }

    private void multiplayerSteps(Result r, Font font, int inner, List<Step> steps) {
        text(steps, font, inner, Component.translatable(KEY + "mp.title"), TITLE_INFO);
        text(steps, font, inner, Component.translatable(KEY + "mp.body"), BODY);
        text(steps, font, inner, Component.translatable(KEY + "mp.sent"), BODY);
        if (r.behind()) {
            text(steps, font, inner, outdated(r).copy().append(" ")
                    .append(Component.translatable(KEY + "outdated.server")), WARN);
        }
    }

    private void lagSteps(Result r, Font font, int inner, List<Step> steps) {
        List<LagTips.Tip> tips = LagTips.applicable(host);
        // No tip applies (often the case on a server): a plain thanks, not a heading over nothing.
        text(steps, font, inner, Component.translatable(BugResponseChat.lagTitleKey(!tips.isEmpty())), TITLE_PLAIN);
        for (LagTips.Tip tip : tips) steps.add(new TipRow(tip));
        if (r.behind()) {
            text(steps, font, inner, outdated(r).copy().append(" ")
                    .append(Component.translatable(KEY + "outdated.update")), WARN);
        }
    }

    private void genericSteps(Result r, Font font, int inner, List<Step> steps) {
        text(steps, font, inner, Component.translatable(KEY + "sent"), TITLE_PLAIN);
        if (r.behind()) {
            Component line = outdated(r);
            if (r.bugFixesMissed() > 0) {
                String plural = plural(r.bugFixesMissed());
                line = line.copy().append(" ").append(Component.translatable(
                        KEY + "outdated.fixes." + plural, r.bugFixesMissed()));
            } else {
                line = line.copy().append(" ").append(Component.translatable(KEY + "outdated.update"));
            }
            text(steps, font, inner, line, WARN);
        }
    }

    private void buttonSteps(Result r, Font font, int inner, List<Step> steps) {
        // No Update button here: the death screen's final page already offers one beside Board anew.
        List<Button> buttons = new ArrayList<>();
        if (r.curseforgeReview() && r.modrinthTarget().isPresent()) {
            buttons.add(new Button(Component.translatable(KEY + "button.get",
                    r.modrinthTarget().get().toString(), Platform.MODRINTH.displayName()), Style.MODRINTH,
                    () -> openLink(MODRINTH_PACK_URL)));
        }
        if (r.behind() || r.kind() == Kind.FIXED) {
            buttons.add(new Button(Component.translatable(KEY + "button.changes"), Style.PLAIN,
                    () -> Minecraft.getInstance().setScreen(new VersionCompareScreen(host))));
        }
        if (!buttons.isEmpty()) steps.add(new ButtonRow(buttons));
    }

    // ---- Helpers ----

    private static Component outdated(Result r) {
        String plural = plural(r.releasesBehind());
        String to = r.updateTarget().map(FullSemver::toString).orElse("?");
        return Component.translatable(KEY + "outdated." + plural, r.releasesBehind(), installedText(r), to);
    }

    /** The plural form the player's language wants for {@code n}, as a lang-key suffix. */
    static String plural(long n) {
        return PluralRules.category(ClientLanguage.selected(), n);
    }

    private static String installedText(Result r) {
        return r.installed().map(FullSemver::toString).orElse(InstalledVersion.display());
    }

    public static String packUrl(Platform p) {
        return p == Platform.CURSEFORGE ? CURSEFORGE_PACK_URL : MODRINTH_PACK_URL;
    }

    private void openLink(String url) {
        ConfirmLinkScreen.confirmLinkNow(host, url);
    }

    private static void text(List<Step> steps, Font font, int inner, Component c, int color) {
        steps.add(new TextBlock(font.split(c, inner), color));
    }

    // ---- Draw steps ----

    private interface Step {
        int height();

        void draw(GuiGraphics g, Font font, Chrome chrome, int x, int y, int w);
    }

    private record TextBlock(List<FormattedCharSequence> lines, int color) implements Step {
        public int height() {
            return lines.size() * LINE + 2;
        }

        public void draw(GuiGraphics g, Font font, Chrome chrome, int x, int y, int w) {
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x, y, chrome.fade(color), false);
                y += LINE;
            }
        }
    }

    /** "• v0.1080.0: Title  [Performance] [Modrinth]", trimmed to one line. */
    private record FixRow(Fix fix) implements Step {
        public int height() {
            return 15;
        }

        public void draw(GuiGraphics g, Font font, Chrome chrome, int x, int y, int w) {
            List<Component> chips = new ArrayList<>();
            if (fix.entry().tags().contains(ChangelogTag.PERFORMANCE)) chips.add(ChangelogTag.PERFORMANCE.label());
            else if (fix.entry().tags().contains(ChangelogTag.FIX)) chips.add(ChangelogTag.FIX.label());
            int chipsW = 0;
            for (Component c : chips) chipsW += font.width(c) + 20;
            Component modrinth = Component.translatable(KEY + "fixed.tag.modrinth_only");
            if (fix.modrinthOnly()) chipsW += font.width(modrinth) + 20;

            String label = "• v" + fix.entry().releasedIn() + ": " + fix.entry().title();
            String shown = font.plainSubstrByWidth(label, Math.max(40, w - chipsW));
            if (shown.length() < label.length()) shown = font.plainSubstrByWidth(label, Math.max(40, w - chipsW - font.width("..."))) + "...";
            g.drawString(font, shown, x, y + 3, chrome.fade(BODY), false);
            int cx = x + font.width(shown) + 4;
            for (Component c : chips) {
                chrome.chip(g, cx, y, c, TAG_BORDER, TAG_TEXT);
                cx += font.width(c) + 20;
            }
            if (fix.modrinthOnly()) chrome.chip(g, cx, y, modrinth, MR_BORDER, MR_TEXT);
        }
    }

    /** A lag tip: text on the left, its button (if any) on the right. */
    private final class TipRow implements Step {
        private final LagTips.Tip tip;

        TipRow(LagTips.Tip tip) {
            this.tip = tip;
        }

        public int height() {
            return 15;
        }

        public void draw(GuiGraphics g, Font font, Chrome chrome, int x, int y, int w) {
            int bw = 0;
            if (tip.button() != null) {
                bw = font.width(tip.button()) + 12;
                int bx = x + w - bw;
                chrome.bevel(g, bx, y, bw, TIP_BTN_H, tip.button(), Style.PLAIN);
                hits.add(new Hit(bx, y, bw, TIP_BTN_H, tip.action()));
            } else if (tip.hint() != null) {
                bw = font.width(tip.hint()) + 4;
                g.drawString(font, tip.hint(), x + w - bw + 4, y + 3, chrome.fade(HINT), false);
            }
            String label = "• " + tip.text().getString();
            String shown = font.plainSubstrByWidth(label, w - bw - 4);
            g.drawString(font, shown, x, y + 3, chrome.fade(BODY), false);
        }
    }

    private record Button(Component label, Style style, Runnable action) {}

    private final class ButtonRow implements Step {
        private final List<Button> buttons;

        ButtonRow(List<Button> buttons) {
            this.buttons = buttons;
        }

        public int height() {
            return BTN_H + 4;
        }

        public void draw(GuiGraphics g, Font font, Chrome chrome, int x, int y, int w) {
            int bx = x;
            for (Button b : buttons) {
                int bw = font.width(b.label()) + 16;
                if (bx + bw > x + w && bx > x) break; // never spill past the card
                chrome.bevel(g, bx, y + 2, bw, BTN_H, b.label(), b.style());
                hits.add(new Hit(bx, y + 2, bw, BTN_H, b.action()));
                bx += bw + 6;
            }
        }
    }
}
