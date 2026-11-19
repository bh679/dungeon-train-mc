package games.brennan.dungeontrain.client.deathpages;

import games.brennan.dungeontrain.client.live.deathpages.LiveDeathChrome;
import games.brennan.dungeontrain.echo.EchoPersonality;
import games.brennan.dungeontrain.echo.EchoSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.client.deathpages.EchoPaint.CLASS;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.FILL;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.LABEL;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.TRACK;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.WORD;

/**
 * The echo page's Stats tab: the personality class, then the three traits as minimal bars ("name:
 * Value" over a one-pixel line) beside a radar hexagon of the six reaction skills, with each skill's
 * icon at its corner. Hovering a corner icon names the skill and gives its score.
 */
final class EchoStatsTab {

    private static final int ROW = 11;
    private static final int BAR_GAP = 2;
    private static final int BAR_AFTER = 6;
    private static final int ICON = 16;
    /** Fill and edge of the skill shape, and the hexagon grid. */
    private static final int SHAPE_FILL = 0x59C9A24A;
    private static final int SHAPE_LINE = 0xFFC9A24A;
    private static final int GRID = 0xFF34363C;

    private record SkillHit(int x, int y, EchoPersonality.Skill skill, int value) {
        boolean has(double mx, double my) {
            return mx >= x && mx < x + ICON && my >= y && my < y + ICON;
        }
    }

    private final LiveDeathChrome chrome;
    private final List<SkillHit> skillHits = new ArrayList<>();
    /** Which of the three words each trait shows; rolled once, so it holds while the page is open. */
    private final int[] variants = new int[EchoPersonality.Trait.values().length];

    EchoStatsTab(LiveDeathChrome chrome) {
        this.chrome = chrome;
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < variants.length; i++) {
            variants[i] = random.nextInt(EchoPersonality.WORDS_PER_SCORE);
        }
    }

    /** The echo's personality name: its egg-word pair ("Friendly, Shy"), or Passive. */
    static Component personalityName(EchoSummary echo) {
        String[] keys = EchoPersonality.personalityKeys(echo.fightFlight(), echo.friendliness());
        if (keys.length == 1) {
            return Component.translatable(keys[0]);
        }
        return Component.translatable(EchoPersonality.PAIR_KEY,
                Component.translatable(keys[0]), Component.translatable(keys[1]));
    }

    private Component word(EchoPersonality.Trait trait, int value) {
        return Component.translatable(EchoPersonality.wordKey(trait, value, variants[trait.ordinal()]));
    }

    /** Draw the tab into the box at ({@code x}, {@code y}), {@code w} × {@code h}. */
    void draw(GuiGraphics g, EchoSummary echo, int x, int y, int w, int h, boolean settled) {
        Font font = chrome.font();
        skillHits.clear();
        Component label = Component.translatable("gui.dungeontrain.death.echo.personality");
        g.drawString(font, label, x, y, chrome.fade(LABEL), false);
        g.drawString(font, personalityName(echo), x + font.width(label) + 4, y,
                chrome.fade(CLASS), false);
        int top = y + ROW + 4;
        int bodyH = Math.max(0, y + h - top);

        // Left: the three traits as minimal bars, as wide as their longest label, centred in height.
        int barsW = Math.max(barsWidth(font, echo), w / 3);
        int barsH = 3 * (ROW - 2 + BAR_GAP + 1 + BAR_AFTER);
        int by = top + Math.max(0, (bodyH - barsH) / 2);
        by = bar(g, font, EchoPersonality.Trait.FIGHT_FLIGHT, echo.fightFlight(), x, by, barsW);
        by = bar(g, font, EchoPersonality.Trait.FRIENDLINESS, echo.friendliness(), x, by, barsW);
        bar(g, font, EchoPersonality.Trait.REACTION, echo.reactionSpeed(), x, by, barsW);

        // Right: the skill hexagon in the rest of the width, icons just outside each corner.
        int hx = x + barsW + 6;
        int hw = x + w - hx;
        float r = Math.max(10, Math.min((hw / 2.0F - ICON - 2) / 0.87F, bodyH / 2.0F - ICON - 1));
        float cx = hx + hw / 2.0F;
        float cy = top + bodyH / 2.0F;
        EchoPersonality.Skill[] skills = EchoPersonality.Skill.values();
        int[] values = new int[skills.length];
        for (int i = 0; i < skills.length; i++) {
            values[i] = echo.skill(skills[i]);
        }
        SkillHexagon.draw(g, cx, cy, r, values, EchoPersonality.MAX,
                chrome.fade(GRID), chrome.fade(SHAPE_FILL), chrome.fade(SHAPE_LINE));
        for (int i = 0; i < skills.length; i++) {
            float[] p = SkillHexagon.corner(cx, cy, r + ICON / 2.0F + 1, i, skills.length);
            int ix = Math.round(p[0] - ICON / 2.0F);
            int iy = Math.round(p[1] - ICON / 2.0F);
            if (settled) {
                EchoPaint.skillIcon(g, skills[i], ix, iy);
            }
            skillHits.add(new SkillHit(ix, iy, skills[i], values[i]));
        }
    }

    /** The widest "trait: Value" label among the three, for the bar column's width. */
    int barsWidth(Font font, EchoSummary echo) {
        int w = 0;
        int[] values = {echo.fightFlight(), echo.friendliness(), echo.reactionSpeed()};
        EchoPersonality.Trait[] traits = EchoPersonality.Trait.values();
        for (int i = 0; i < traits.length; i++) {
            Component label = Component.translatable(traits[i].labelKey()).append(": ")
                    .append(word(traits[i], values[i]));
            w = Math.max(w, font.width(label));
        }
        return w;
    }

    /** Width the tab needs: the bar column plus a hexagon of radius {@code 28} with its corner icons. */
    int minWidth(Font font, EchoSummary echo) {
        return barsWidth(font, echo) + 6 + Math.round(2 * (28 * 0.87F + ICON + 2));
    }

    /** "trait: Value" over a one-pixel bar {@code w} wide; returns the y below it. */
    private int bar(GuiGraphics g, Font font, EchoPersonality.Trait trait, int value, int x, int y, int w) {
        Component label = Component.translatable(trait.labelKey()).append(": ");
        g.drawString(font, label, x, y, chrome.fade(LABEL), false);
        g.drawString(font, word(trait, value),
                x + font.width(label), y, chrome.fade(WORD), false);
        int ly = y + ROW - 2 + BAR_GAP;
        g.fill(x, ly, x + w, ly + 1, chrome.fade(TRACK));
        int fillW = Math.round(w * EchoPersonality.clamp(value) / (float) EchoPersonality.MAX);
        if (fillW > 0) {
            g.fill(x, ly, x + fillW, ly + 1, chrome.fade(FILL));
        }
        return ly + 1 + BAR_AFTER;
    }

    /** The hovered skill's name and score, if the cursor is on a corner icon. */
    boolean tooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (SkillHit hit : skillHits) {
            if (hit.has(mouseX, mouseY)) {
                g.renderTooltip(chrome.font(), Component.translatable("gui.dungeontrain.death.echo.skill_tip",
                        Component.translatable(hit.skill().langKey()), hit.value()), mouseX, mouseY);
                return true;
            }
        }
        return false;
    }
}
