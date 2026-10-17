package games.brennan.dungeontrain.client.live.deathpages;

import games.brennan.dungeontrain.client.DeathScreenText;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory.Pitch;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.ChatMoment;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.Watcher;
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
 * "The crowd": the death page about who watched. A podium of the three who watched this life the
 * longest (skin face, name, watch time, where they watched from), the crowd's numbers, two chat
 * lines from the final minute, and the community reward pitch, which changes with what happened to
 * the broadcast and what is already on ({@link LiveDeathStory#pitch}). The buyer is thanked by name.
 */
public final class CrowdPage {

    private static final int NAME = 0xFFFFFFFF;
    private static final int WATCH = 0xFFE0B56A;
    private static final int CHAT_BG = 0x66000000;
    private static final int CHAT_AUTHOR = 0xFF8FE08A;
    private static final int PODIUM_W = 88;
    private static final int PODIUM_GAP = 10;
    private static final int BUTTON_W = 170;
    private static final int BUTTON_H = 16;

    private final LiveDeathChrome chrome;
    private int btnX, btnY, btnW;

    public CrowdPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /** Whether "See Community Rewards" is under the cursor. */
    public boolean buttonAt(double mx, double my) {
        return btnW > 0 && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + BUTTON_H;
    }

    /** Draw the page body from {@code y}; returns the y below it. {@code compact} drops the train and chat. */
    public int draw(GuiGraphics g, LiveLifeStats s, LiveRewards rewards, long now, long deathMs,
                    int left, int w, int cx, int y, boolean compact) {
        Font font = chrome.font();
        btnW = 0;
        DeathScreenText.drawCenteredLine(g, font, tr("crowd.kicker"), cx, y, chrome.fade(DeathScreenText.KICKER));
        y += DeathScreenText.KICKER_STEP;
        if (!compact) {
            chrome.train(g, left, w, y);
            y += DeathScreenText.TRAIN_STEP;
        }
        y = DeathScreenText.drawNarration(g, font, narration(s), cx, w, y, chrome.fade(DeathScreenText.NARR));
        y += 4;
        if (!s.crowd().isEmpty() && s.peakViewers() > 0) y = drawPodium(g, s.crowd(), cx, y) + 4;

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

        List<ChatMoment> last = s.finalMinute(deathMs > 0 ? deathMs : now, 2);
        if (!compact && !last.isEmpty()) y = drawChat(g, last, left, w, y) + 4;

        Pitch pitch = LiveDeathStory.pitch(s, rewards, now);
        boolean quicker = rewards.active(LiveRewards.QUICKER_STREAMS, now);
        int delay = LiveDeathStory.delaySeconds(s.segmentSeconds(), quicker);
        y = DeathScreenText.drawQuestion(g, font, question(pitch, rewards, delay), cx, w, y, chrome.fade(DeathScreenText.QUESTION));
        y = DeathScreenText.drawNarration(g, font, pitchText(pitch, rewards, now), cx, w, y - 2, chrome.fade(DeathScreenText.NARR));
        y += 4;
        btnX = cx - BUTTON_W / 2;
        btnY = y;
        btnW = BUTTON_W;
        chrome.bevel(g, btnX, btnY, BUTTON_W, BUTTON_H, tr("crowd.button"), LiveDeathChrome.Style.PURPLE);
        return y + BUTTON_H;
    }

    private static String narration(LiveLifeStats s) {
        if (s.peakViewers() <= 0) return str("crowd.narr.nobody");
        String who = num(s.peakViewers());
        return switch (Math.min(3, s.crowd().size())) {
            case 3 -> str("crowd.narr.three", who);
            case 2 -> str("crowd.narr.two", who);
            case 1 -> str("crowd.narr.one", who);
            default -> str("crowd.narr.plain", who);
        };
    }

    private int drawPodium(GuiGraphics g, List<Watcher> top, int cx, int y) {
        Font font = chrome.font();
        int n = Math.min(3, top.size());
        int total = n * PODIUM_W + (n - 1) * PODIUM_GAP;
        int x0 = cx - total / 2;
        int bottom = y;
        for (int i = 0; i < n; i++) {
            Watcher wt = top.get(i);
            int colCx = x0 + i * (PODIUM_W + PODIUM_GAP) + PODIUM_W / 2;
            int size = i == 0 ? 24 : 18;
            int fy = y + (24 - size);
            WatcherFaces.draw(g, "game".equals(wt.source()) ? wt.uuid() : null, colCx - size / 2, fy, size, chrome.alpha());
            int ty = y + 24 + 3;
            String name = wt.name() != null ? wt.name() : str("web".equals(wt.source()) ? "viewer.web" : "viewer.game");
            drawFitted(g, name, colCx, ty, PODIUM_W - 2, NAME);
            ty += font.lineHeight + 1;
            drawFitted(g, duration(wt.seconds() * 1000L), colCx, ty, PODIUM_W - 2, WATCH);
            ty += font.lineHeight + 1;
            drawFitted(g, str("web".equals(wt.source()) ? "source.web" : "source.game"), colCx, ty, PODIUM_W - 2, DeathScreenText.SUBLINE);
            bottom = Math.max(bottom, ty + font.lineHeight);
        }
        return bottom;
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

    private void drawFitted(GuiGraphics g, String text, int cx, int y, int maxW, int color) {
        Font font = chrome.font();
        String s = fit(font, text, maxW);
        g.drawString(font, s, cx - font.width(s) / 2, y, chrome.fade(color), false);
    }

    /** {@code text} cut to {@code maxW} px with an ellipsis. */
    static String fit(Font font, String text, int maxW) {
        if (text == null) return "";
        if (font.width(text) <= maxW) return text;
        String dots = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxW - font.width(dots))) + dots;
    }
}
