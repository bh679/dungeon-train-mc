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
 * Value" over a one-pixel line) beside a radar graph of the six reaction skills and the two hobbies
 * (Reading, Trading), with each one's icon at its corner. Hovering a corner icon names it and gives
 * its score.
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

    /** One corner of the graph: a reaction skill or a hobby, by lang key and icon. */
    private record Corner(String langKey, EchoPersonality.Skill skill, EchoPersonality.Hobby hobby) {
        int value(EchoSummary echo) {
            return skill != null ? echo.skill(skill) : echo.hobby(hobby);
        }

        void icon(GuiGraphics g, int x, int y) {
            if (skill != null) {
                EchoPaint.skillIcon(g, skill, x, y);
            } else {
                EchoPaint.hobbyIcon(g, hobby, x, y);
            }
        }
    }

    /** The graph's corners clockwise from the top: the six skills, then the hobbies. */
    private static final List<Corner> CORNERS = corners();

    private static List<Corner> corners() {
        List<Corner> out = new ArrayList<>();
        for (EchoPersonality.Skill skill : EchoPersonality.Skill.values()) {
            out.add(new Corner(skill.langKey(), skill, null));
        }
        for (EchoPersonality.Hobby hobby : EchoPersonality.Hobby.values()) {
            out.add(new Corner(hobby.langKey(), null, hobby));
        }
        return List.copyOf(out);
    }

    private record SkillHit(int x, int y, String langKey, int value) {
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
        int personalityEnd = x + font.width(label) + 4 + font.width(personalityName(echo));
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

        // Right: the skill graph in the rest of the width, icons just outside each corner, as high
        // as it goes.
        int n = CORNERS.size();
        float halfWidth = SkillHexagon.halfWidth(n);
        int hx = x + barsW + 6;
        int hw = x + w - hx;
        float r = Math.max(10, Math.min((hw / 2.0F - ICON - 2) / halfWidth, bodyH / 2.0F - ICON - 1));
        float cx = hx + hw / 2.0F;
        // As high as it goes: the top corner's icon rises level with the personality row when the
        // personality name ends short of it, else it sits just under that row.
        boolean clear = personalityEnd + 4 < Math.round(cx - ICON / 2.0F);
        float cy = (clear ? y : top) + r + ICON + 1;
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = CORNERS.get(i).value(echo);
        }
        SkillHexagon.draw(g, cx, cy, r, values, EchoPersonality.MAX,
                chrome.fade(GRID), chrome.fade(SHAPE_FILL), chrome.fade(SHAPE_LINE));
        for (int i = 0; i < n; i++) {
            float[] p = SkillHexagon.corner(cx, cy, r + ICON / 2.0F + 1, i, n);
            int ix = Math.round(p[0] - ICON / 2.0F);
            int iy = Math.round(p[1] - ICON / 2.0F);
            if (settled) {
                CORNERS.get(i).icon(g, ix, iy);
            }
            skillHits.add(new SkillHit(ix, iy, CORNERS.get(i).langKey(), values[i]));
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

    /** Width the tab needs: the bar column plus a graph of radius {@code 28} with its corner icons. */
    int minWidth(Font font, EchoSummary echo) {
        return barsWidth(font, echo) + 6 + Math.round(2 * (28 * SkillHexagon.halfWidth(CORNERS.size()) + ICON + 2));
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
                        Component.translatable(hit.langKey()), hit.value()), mouseX, mouseY);
                return true;
            }
        }
        return false;
    }
}
