package games.brennan.dungeontrain.client.live.deathpages;

import games.brennan.dungeontrain.client.DeathScreenText;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory;
import games.brennan.dungeontrain.client.live.life.LiveDeathStory.Story;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import games.brennan.dungeontrain.client.support.RewardsLink;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.clock;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.date;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.duration;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.num;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.str;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.time;
import static games.brennan.dungeontrain.client.live.deathpages.LiveDeathText.tr;

/**
 * "The broadcast": the death page about the player and their stream. Time on air over every stint of
 * the life, the moments and photos they put on air, how many times they picked the Eye up, the delay
 * their viewers lived with, the death clip and who took the Eye after them, and whether the stream
 * outlives them (Replays Kept), with a small aqua link to the Community Rewards page (hidden until {@link RewardsLink#PAGE_READY}).
 */
public final class BroadcastPage {

    private static final int CLIP_BG = 0xFF211E1A;
    private static final int THUMB_BG = 0xFF1B1B1F;
    private static final int THUMB_BORDER = 0xFF000000;
    private static final int LINK = 0xFF55FFFF;
    private static final int CLIP_H = 52;
    private static final int THUMB_W = 84;

    private final LiveDeathChrome chrome;
    private int linkX, linkY, linkW, linkH;

    public BroadcastPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /** Whether the Community Rewards link on the status line is under the cursor. */
    public boolean linkAt(double mx, double my) {
        return linkW > 0 && mx >= linkX && mx < linkX + linkW && my >= linkY && my < linkY + linkH;
    }

    /**
     * Draw the page body from {@code y}; returns the y below it. The train is left out when the page
     * would otherwise reach {@code maxY} (the Continue row).
     */
    public int draw(GuiGraphics g, LiveLifeStats s, LiveRewards rewards, long now, long deathMs,
                    int left, int w, int cx, int y, int maxY) {
        Font font = chrome.font();
        linkW = 0;
        boolean quicker = rewards.active(LiveRewards.QUICKER_STREAMS, now);
        int delay = LiveDeathStory.delaySeconds(s.segmentSeconds(), quicker);
        Story story = LiveDeathStory.story(s);
        String narration = narration(story, s, now, deathMs, delay);
        int narrH = font.split(DeathScreenText.styled(narration), w - 8).size() * (font.lineHeight + 2) + 4;
        int body = DeathScreenText.KICKER_STEP + narrH + 6 + 32 + CLIP_H + 6 + 2 * (font.lineHeight + 2);
        boolean showTrain = body + DeathScreenText.TRAIN_STEP <= maxY - y;

        DeathScreenText.drawCenteredLine(g, font, tr("broadcast.kicker"), cx, y, chrome.fade(DeathScreenText.KICKER));
        y += DeathScreenText.KICKER_STEP;
        if (showTrain) {
            chrome.train(g, left, w, y);
            y += DeathScreenText.TRAIN_STEP;
        }
        y = DeathScreenText.drawNarration(g, font, narration, cx, w, y, chrome.fade(DeathScreenText.NARR));
        y += 6;

        int gap = 4;
        int cw = (w - gap * 4) / 5;
        String[] values = {clock(s.onAirMs(now)), Integer.toString(s.moments()), Integer.toString(s.photos()),
            Integer.toString(s.stints()), str("secs", delay)};
        String[] labels = {"lbl.on_air", "lbl.moments", "lbl.photos", "lbl.stints", "lbl.delay"};
        for (int i = 0; i < 5; i++) {
            chrome.cell(g, left + i * (cw + gap) + cw / 2, y, values[i], tr(labels[i]), cw);
        }
        y += 32;

        y = drawClip(g, s, story, left, w, y);
        y += 6;
        return drawStatus(g, rewards, now, cx, w, y);
    }

    private static String narration(Story story, LiveLifeStats s, long now, long deathMs, int delay) {
        String onAir = num(duration(s.onAirMs(now)));
        return switch (story) {
            case TO_THE_END -> str("broadcast.narr.to_the_end", onAir, num(str("secs", delay)));
            case ENDED_EARLY -> str("broadcast.narr.ended_early", onAir,
                num(duration(Math.max(0, deathMs - s.lastStopMs()))));
            case TAKEN -> str("broadcast.narr.taken", onAir, num(s.takenBy()));
            case NOBODY -> str("broadcast.narr.nobody", onAir);
        };
    }

    private int drawClip(GuiGraphics g, LiveLifeStats s, Story story, int left, int w, int y) {
        Font font = chrome.font();
        g.fill(left, y, left + w, y + CLIP_H, chrome.fade(CLIP_BG));
        int tx = left + 3, ty = y + 3, th = CLIP_H - 6;
        g.fill(tx, ty, tx + THUMB_W, ty + th, chrome.fade(THUMB_BORDER));
        g.fill(tx + 1, ty + 1, tx + THUMB_W - 1, ty + th - 1, chrome.fade(THUMB_BG));
        Component thumb = s.diedOnAir() ? tr("clip.thumb_clip") : tr("clip.thumb_none");
        DeathScreenText.drawCenteredLine(g, font, thumb, tx + THUMB_W / 2, ty + (th - font.lineHeight) / 2,
            chrome.fade(DeathScreenText.KICKER));

        Component head;
        Component tail;
        if (story == Story.TAKEN) {
            head = tr("clip.taken_head", s.takenBy());
            tail = tr("clip.taken_tail");
        } else if (!s.diedOnAir()) {
            head = tr("clip.none_head");
            tail = s.lastStopMs() > 0 ? tr("clip.none_tail", time(s.lastStopMs())) : Component.empty();
        } else if (story == Story.NOBODY) {
            head = tr("clip.nobody_head");
            tail = tr("clip.nobody_tail");
        } else {
            head = tr("clip.cut_head");
            tail = !s.takenBy().isEmpty() ? tr("clip.after_taken", s.takenBy())
                : s.live() ? tr("clip.after_open") : tr("clip.after_ended");
        }
        int textX = tx + THUMB_W + 6;
        int textW = left + w - 4 - textX;
        int ly = y + 5;
        for (var line : font.split(head, textW)) {
            g.drawString(font, line, textX, ly, chrome.fade(DeathScreenText.NARR), false);
            ly += font.lineHeight + 1;
        }
        for (var line : font.split(tail, textW)) {
            if (ly > y + CLIP_H - font.lineHeight) break;
            g.drawString(font, line, textX, ly, chrome.fade(DeathScreenText.SUBLINE), false);
            ly += font.lineHeight + 1;
        }
        return y + CLIP_H;
    }

    private int drawStatus(GuiGraphics g, LiveRewards rewards, long now, int cx, int w, int y) {
        Font font = chrome.font();
        LiveRewards.Reward replays = rewards.find(LiveRewards.REPLAYS_KEPT);
        Component status = replays != null && replays.activeUntilMs() > now
            ? tr("status.replays_on", date(replays.activeUntilMs()))
            : tr("status.replays_off");
        if (!RewardsLink.PAGE_READY) {
            return DeathScreenText.drawCentered(g, font, status, cx, w, y, chrome.fade(DeathScreenText.SUBLINE));
        }
        MutableComponent link = tr("status.link").copy()
            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(LINK & 0xFFFFFF)).withUnderlined(true));
        int sw = font.width(status);
        int lw = font.width(link);
        int space = font.width(" ");
        if (sw + space + lw <= w - 8) {
            int x = cx - (sw + space + lw) / 2;
            g.drawString(font, status, x, y, chrome.fade(DeathScreenText.SUBLINE), false);
            setLink(x + sw + space, y, lw, font.lineHeight);
            g.drawString(font, link, linkX, y, chrome.fade(LINK), false);
            return y + font.lineHeight + 2;
        }
        y = DeathScreenText.drawCentered(g, font, status, cx, w, y,
            chrome.fade(DeathScreenText.SUBLINE));
        setLink(cx - lw / 2, y, lw, font.lineHeight);
        g.drawString(font, link, linkX, y, chrome.fade(LINK), false);
        return y + font.lineHeight + 2;
    }

    private void setLink(int x, int y, int w, int h) {
        linkX = x;
        linkY = y - 1;
        linkW = w;
        linkH = h + 2;
    }
}
