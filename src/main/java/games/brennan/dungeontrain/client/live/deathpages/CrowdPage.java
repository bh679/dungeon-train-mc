package games.brennan.dungeontrain.client.live.deathpages;

import games.brennan.dungeontrain.client.DeathScreenText;
import games.brennan.dungeontrain.client.live.life.CrowdRanking;
import games.brennan.dungeontrain.client.live.life.CrowdRanking.Ranked;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory.Pitch;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.ChatMoment;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.date;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.duration;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.num;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.str;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.tr;

/**
 * "The crowd": the death page about who watched. A line about the whole crowd, then the one viewer
 * most present this life, by watch time and chat lines ({@link CrowdRanking}), standing as their whole
 * skin with their name, watch time, lines said and where they watched from, the crowd's numbers, two chat
 * lines from the final minute, and the community reward pitch, which changes with what happened to
 * the broadcast and what is already on ({@link LiveDeathStory#pitch}). The buyer is thanked by name.
 */
public final class CrowdPage {

    private static final int NAME = 0xFFFFFFFF;
    private static final int WATCH = 0xFFE0B56A;
    private static final int CHAT_BG = 0x66000000;
    private static final int CHAT_AUTHOR = 0xFF8FE08A;
    private static final int PERSON_GAP = 10;
    /** The figure's inset from the content column's left edge. */
    private static final int ROW_INDENT = 6;
    /** The player info column never grows past this; a longer name is cut with an ellipsis. */
    private static final int INFO_MAX_W = 100;
    private static final int BUTTON_W = 170;
    private static final int BUTTON_H = 16;
    /** First place's full figure, feet to crown. */
    private static final int FIGURE_H = 50;
    /** The figure's height on a short screen, before it gives way to a face. */
    private static final int FIGURE_MIN_H = 38;
    private static final int FACE_AREA = 24;

    private final LiveDeathChrome chrome;
    private final WatcherFigure figure = new WatcherFigure();
    private int btnX, btnY, btnW;

    public CrowdPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /** Drop the first-place figure's cached entity. Call from the screen's {@code removed()}. */
    public void clear() {
        figure.clear();
    }

    /** Whether "See Community Rewards" is under the cursor. */
    public boolean buttonAt(double mx, double my) {
        return btnW > 0 && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + BUTTON_H;
    }

    /**
     * Draw the page body from {@code y}, keeping it above {@code maxY} (the Continue row): on a short
     * screen the chat lines go first, then the train, then the podium's "where they watched" line,
     * then the question over the pitch, then the viewer's figure shrinks and gives way to a face, and
     * only last does the line about the crowd go.
     * Returns the y below it.
     */
    public int draw(GuiGraphics g, LiveLifeStats s, LiveRewards rewards, long now, long deathMs,
                    int left, int w, int cx, int y, int maxY) {
        Font font = chrome.font();
        btnW = 0;
        List<Ranked> top = CrowdRanking.podium(s);
        boolean podium = !top.isEmpty();
        List<ChatMoment> last = s.finalMinute(deathMs > 0 ? deathMs : now, 2);
        Pitch pitch = LiveDeathStory.pitch(s, rewards, now);
        boolean quicker = rewards.active(LiveRewards.QUICKER_STREAMS, now);
        int delay = LiveDeathStory.delaySeconds(s.segmentSeconds(), quicker);
        String question = question(pitch, rewards, delay);
        String pitchText = pitchText(pitch, rewards, now);

        int narrH = lines(font, narration(s, top.size()), w) + 8; // the line alone, when nobody is shown
        int questionH = lines(font, question, w) + 4;
        int pitchH = lines(font, pitchText, w) + 2;
        String narration = narration(s, top.size());
        boolean chat = !last.isEmpty(), train = true, source = true, ask = true;
        int figureH = FIGURE_H;
        int budget = maxY - y;
        for (int pass = 0; pass < 8; pass++) {
            int h = DeathScreenText.KICKER_STEP + 32 + pitchH + 4 + BUTTON_H
                + (train ? DeathScreenText.TRAIN_STEP : 0)
                + (podium ? rowHeight(font, narration, top.get(0), w, source, figureH) + 6 : narrH)
                + (chat ? last.size() * (font.lineHeight + 2) + 8 : 0)
                + (ask ? questionH : 0);
            if (h <= budget) break;
            if (chat) chat = false;
            else if (train) train = false;
            else if (source) source = false;
            else if (ask) ask = false;
            else if (figureH == FIGURE_H) figureH = FIGURE_MIN_H;
            else figureH = 0;
        }

        DeathScreenText.drawCenteredLine(g, font, tr("crowd.kicker"), cx, y, chrome.fade(DeathScreenText.KICKER));
        y += DeathScreenText.KICKER_STEP;
        if (train) {
            chrome.train(g, left, w, y);
            y += DeathScreenText.TRAIN_STEP;
        }
        if (podium) {
            y = drawRow(g, narration, top.get(0), left, w, y + 4, source, figureH) + 6;
        } else {
            y = DeathScreenText.drawNarration(g, font, narration, cx, w, y, chrome.fade(DeathScreenText.NARR));
            y += 4;
        }

        int gap = 4;
        int cw = (w - gap * 4) / 5;
        String said = s.mostSaid();
        String[] values = {Integer.toString(s.peakViewers()), Integer.toString(s.chatLines()),
            str("votes", s.votesWon(), s.votesClosed()), said.isEmpty() ? str("nobody") : said,
            Integer.toString(s.reactionTotal())};
        String[] labels = {"lbl.peak", "lbl.chat", "lbl.votes_won", "lbl.most_said", "lbl.reactions"};
        for (int i = 0; i < 5; i++) {
            chrome.cell(g, left + i * (cw + gap) + cw / 2, y, fit(font, values[i], cw - 4), tr(labels[i]), cw);
        }
        y += 32;

        if (chat) y = drawChat(g, last, left, w, y) + 4;
        if (ask) y = DeathScreenText.drawQuestion(g, font, question, cx, w, y, chrome.fade(DeathScreenText.QUESTION));
        y = DeathScreenText.drawNarration(g, font, pitchText, cx, w, y - 2, chrome.fade(DeathScreenText.NARR));
        y += 4;
        btnX = cx - BUTTON_W / 2;
        btnY = Math.min(y, maxY - BUTTON_H); // a translation too long for even the compact page keeps the button clear of Continue
        btnW = BUTTON_W;
        chrome.bevel(g, btnX, btnY, BUTTON_W, BUTTON_H, tr("crowd.button"), LiveDeathChrome.Style.PURPLE);
        return btnY + BUTTON_H;
    }

    /** Height of {@code text} wrapped as the narration wraps it. */
    private static int lines(Font font, String text, int w) {
        return font.split(DeathScreenText.styled(text), w - 8).size() * (font.lineHeight + 2);
    }

    /**
     * One line about the whole crowd: how many came and how much they said, then, when someone is
     * shown, that one of them stayed closer than the rest.
     */
    private static String narration(LiveLifeStats s, int placed) {
        if (s.peakViewers() <= 0) return str("crowd.narr.nobody");
        String who = num(s.peakViewers());
        String line = s.chatLines() > 0
            ? str("crowd.narr.community", who, num(s.chatLines()))
            : str("crowd.narr.quiet", who);
        return placed > 0 ? line + " " + str("crowd.narr.closest") : line;
    }

    private static int figureWidth(int figureH) {
        return figureH > 0 ? Math.max(20, figureH * 3 / 5) : FACE_AREA;
    }

    /** The text column beside the figure: the crowd line, then the viewer's name and stats. */
    private static List<net.minecraft.util.FormattedCharSequence> narrationLines(Font font, String narration, int textW) {
        return font.split(DeathScreenText.styled(narration), textW);
    }

    /** The info column, one fact per line: name, watch time, lines said, where they watched from. */
    private record Info(String text, int color) {}

    private static List<Info> infoLines(Ranked r, boolean source) {
        List<Info> out = new java.util.ArrayList<>();
        out.add(new Info(displayName(r), NAME));
        if (r.seconds() > 0) out.add(new Info(duration(r.seconds() * 1000L), WATCH));
        if (r.messages() > 0) out.add(new Info(str("crowd.messages", r.messages()), DeathScreenText.NARR));
        String where = source ? sourceKey(r) : null;
        if (where != null) out.add(new Info(where.startsWith("=") ? where.substring(1) : str(where), DeathScreenText.SUBLINE));
        return out;
    }

    private static int infoWidth(Font font, List<Info> info) {
        int iw = 0;
        for (Info l : info) iw = Math.max(iw, font.width(l.text()));
        return Math.min(INFO_MAX_W, iw);
    }

    private static int rowHeight(Font font, String narration, Ranked r, int w, boolean source, int figureH) {
        List<Info> info = infoLines(r, source);
        int textW = w - ROW_INDENT - figureWidth(figureH) - PERSON_GAP - infoWidth(font, info) - PERSON_GAP;
        int lh = font.lineHeight + 1;
        int textH = narrationLines(font, narration, textW).size() * (lh + 1);
        return Math.max(Math.max(figureH, FACE_AREA), Math.max(info.size() * lh, textH));
    }

    /**
     * Three columns: the viewer most present this life as their whole skin {@code figureH} tall (a
     * face when that is 0), their name and stats one per line, then the line about the whole crowd.
     * Both text columns sit centred on the row.
     */
    private int drawRow(GuiGraphics g, String narration, Ranked r, int left, int w, int y, boolean source, int figureH) {
        Font font = chrome.font();
        String uuid = "game".equals(r.source()) ? r.uuid() : null;
        int figW = figureWidth(figureH);
        int h = rowHeight(font, narration, r, w, source, figureH);
        int figCx = left + ROW_INDENT + figW / 2;
        boolean drawn = false;
        if (figureH > 0 && chrome.alpha() >= 0.95f) drawn = figure.draw(g, uuid, r.name(), figCx, y + h, figureH);
        if (!drawn) WatcherFaces.draw(g, uuid, figCx - FACE_AREA / 2, y + (h - FACE_AREA) / 2, FACE_AREA, chrome.alpha());

        int lh = font.lineHeight + 1;
        List<Info> info = infoLines(r, source);
        int ix = left + ROW_INDENT + figW + PERSON_GAP;
        int iw = infoWidth(font, info);
        int iy = y + (h - info.size() * lh) / 2;
        for (Info line : info) {
            g.drawString(font, fit(font, line.text(), iw), ix, iy, chrome.fade(line.color()), false);
            iy += lh;
        }

        int tx = ix + iw + PERSON_GAP;
        int textW = left + w - tx;
        var lines = narrationLines(font, narration, textW);
        int ty = y + (h - lines.size() * (lh + 1)) / 2;
        for (var line : lines) {
            g.drawString(font, line, tx, ty, chrome.fade(DeathScreenText.NARR), false);
            ty += lh + 1;
        }
        return y + h;
    }

    private static String displayName(Ranked r) {
        if (r.name() != null && !r.name().isBlank()) return r.name();
        return str("web".equals(r.source()) ? "viewer.web" : "viewer.game");
    }

    /** The lang key for where they watched, "=Name" for a platform name shown as is, null when unknown. */
    private static String sourceKey(Ranked r) {
        return switch (r.source()) {
            case "game" -> "source.game";
            case "web" -> "source.web";
            case "twitch" -> "=Twitch";
            case "discord" -> "=Discord";
            default -> null;
        };
    }

    private int drawChat(GuiGraphics g, List<ChatMoment> lines, int left, int w, int y) {
        Font font = chrome.font();
        int h = lines.size() * (font.lineHeight + 2) + 4;
        g.fill(left, y, left + w, y + h, chrome.fade(CHAT_BG));
        int ly = y + 3;
        for (ChatMoment c : lines) {
            String author = fit(font, c.author(), w / 3);
            int ax = left + 6;
            g.drawString(font, author, ax, ly, chrome.fade(CHAT_AUTHOR), false);
            int tx = ax + font.width(author) + font.width(" ");
            g.drawString(font, fit(font, c.text(), left + w - 6 - tx), tx, ly, chrome.fade(DeathScreenText.NARR), false);
            ly += font.lineHeight + 2;
        }
        return y + h;
    }

    private static String question(Pitch pitch, LiveRewards rewards, int delay) {
        return switch (pitch) {
            case QUICKER_ASK -> str("pitch.quicker_ask.q", num(delay));
            case QUICKER_THANKS -> str("pitch.quicker_thanks.q", num(LiveDeathStory.QUICKER_DELAY_S), buyer(rewards, LiveRewards.QUICKER_STREAMS));
            case REPLAYS_ASK -> str("pitch.replays_ask.q");
            case TWO_STREAMS_ASK -> str("pitch.two_streams_ask.q");
            case NOBODY -> str("pitch.nobody.q");
        };
    }

    private static String pitchText(Pitch pitch, LiveRewards rewards, long now) {
        return switch (pitch) {
            case QUICKER_ASK -> str("pitch.quicker_ask.p", num(LiveDeathStory.QUICKER_DELAY_S));
            case QUICKER_THANKS -> {
                LiveRewards.Reward r = rewards.find(LiveRewards.QUICKER_STREAMS);
                String until = r == null ? "" : date(r.activeUntilMs());
                yield r != null && !r.queuedBy().isEmpty() && !r.queuedBy().get(0).isEmpty()
                    ? str("pitch.quicker_thanks.p_queued", until, r.queuedBy().get(0))
                    : str("pitch.quicker_thanks.p", until);
            }
            case REPLAYS_ASK -> str("pitch.replays_ask.p");
            case TWO_STREAMS_ASK -> str("pitch.two_streams_ask.p");
            case NOBODY -> rewards.active(LiveRewards.QUICKER_STREAMS, now)
                ? str("pitch.nobody.p_on", buyer(rewards, LiveRewards.QUICKER_STREAMS))
                : str("pitch.nobody.p_off");
        };
    }

    private static String buyer(LiveRewards rewards, String id) {
        LiveRewards.Reward r = rewards.find(id);
        return r == null || r.by().isEmpty() ? str("someone") : r.by();
    }

    /** {@code text} cut to {@code maxW} px with an ellipsis. */
    static String fit(Font font, String text, int maxW) {
        if (text == null) return "";
        if (font.width(text) <= maxW) return text;
        String dots = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxW - font.width(dots))) + dots;
    }
}
