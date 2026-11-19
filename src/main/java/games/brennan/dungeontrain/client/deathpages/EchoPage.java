package games.brennan.dungeontrain.client.deathpages;

import games.brennan.dungeontrain.client.DeathPortraitRenderer;
import games.brennan.dungeontrain.client.live.deathpages.LiveDeathChrome;
import games.brennan.dungeontrain.echo.EchoPersonality;
import games.brennan.dungeontrain.echo.EchoSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.List;

/**
 * "The echo": the death page about the echo this death left behind, laid out like the vanilla
 * inventory in the death screen's own colours. Left: the armour slots with the offhand under them,
 * and a large animated portrait as tall as the card. Right: name and depth, personality and the
 * trait bars, then along the bottom the in-game armour bar over a hotbar-style row (main hand first,
 * then what the echo carries in its backpack).
 *
 * <p>The text column ends with reaction speed: one bar for the overall score, then a row of six
 * bare item icons, one per PlayerMob reaction skill (melee, ranged, defence, building, movement,
 * items), each over a mini bar of its 0–10 score; hover names the skill and gives the number.</p>
 *
 * <p>Items, sprites and the portrait can't take the page fade's alpha, so they draw only once the
 * page has settled (the screen passes {@code settled}). Owns its portrait renderer: call
 * {@link #clear()} from the screen's {@code removed()}.</p>
 */
public final class EchoPage {

    private static final int SLOT = 18;
    private static final int GAP = 2;
    private static final int OFFHAND_GAP = 10;
    /** A reaction skill: a bare 16 px item icon over a mini bar, with this much room between them. */
    private static final int SKILL_ICON = 16;
    private static final int SKILL_BAR_GAP = 2;
    private static final int SKILL_BAR_H = 2;
    private static final int SKILL_H = SKILL_ICON + SKILL_BAR_GAP + SKILL_BAR_H;
    private static final int SKILL_STEP = SKILL_ICON + 10;
    private static final int EDGE = 6;

    /** The right column's widest row: the nine-slot hotbar. */
    private static final int HOTBAR_W = (1 + EchoSummary.MAX_CARRIED) * (SLOT + GAP) - GAP;
    /** Content height range: the smallest GUI (240 px tall) gets the minimum. */
    private static final int MIN_CONTENT_H = 122;
    private static final int MAX_CONTENT_H = 200;
    private static final int BASE_STEP = 11;
    private static final int BAR_LABEL = 9;
    private static final int BASE_BAR_H = 3;
    private static final int BASE_BAR_GAP = 3;
    /** Rows in the right column that take a share of any extra height. */
    private static final int SPACED_ROWS = 7;
    /** Head room the idle animation needs above the model, in model heights (looks, helmet). */
    private static final float PORTRAIT_FIT = 2.25F;
    /** The echo is drawn a little smaller than the column allows, leaving room for its name tag. */
    private static final float PORTRAIT_SCALE = 0.9F;
    /** Room the name tag takes at the top of the echo's column. */
    private static final int NAME_TAG_H = 12;
    /** The in-game name tag's backing: a quarter-black strip, as vanilla draws it. */
    private static final int NAME_TAG_BG = 0x40000000;

    /**
     * The panel's measurements for the room it has. Left: the armour slots with the offhand under
     * them, and a portrait as tall as the whole card. Right: the name line, personality, the trait
     * bars and the skill icons, with the armour bar and hotbar along the bottom. The card fills the room down to the footer; extra height
     * goes to the portrait and spreads the right column's rows.
     */
    private record Layout(int pad, int contentH, int portraitW, int portraitSize, int textW,
                          int step, int barH) {
        /**
         * {@code minTextW} is the widest row the right column must hold (the personality row with
         * the carriage number); the column is otherwise just the hotbar's width, and the echo's
         * side takes everything else.
         */
        static Layout fit(int room, int width, int minTextW) {
            int pad = room >= 160 ? EDGE : 3;
            int contentH = Math.max(MIN_CONTENT_H, Math.min(MAX_CONTENT_H, room - 2 * pad));
            int textW = Math.max(HOTBAR_W, minTextW);
            int portraitW = Math.max(60, Math.min(170, width - (EDGE + SLOT + EDGE + EDGE + textW + EDGE)));
            int extra = contentH - MIN_CONTENT_H;
            int step = extra / SPACED_ROWS;
            int barH = BASE_BAR_H + Math.min(2, extra / 30);
            // The name tag takes the top NAME_TAG_H px; the echo stands below it with head room.
            int size = Math.round((contentH - NAME_TAG_H - 6) / PORTRAIT_FIT * PORTRAIT_SCALE);
            return new Layout(pad, contentH, portraitW, size, textW, step, barH);
        }

        int panelW() {
            return EDGE + SLOT + EDGE + portraitW + EDGE + textW + EDGE;
        }

        int panelH() {
            return pad + contentH + pad;
        }
    }

    /**
     * The item that stands for each reaction skill, in {@link EchoPersonality.Skill} order. Movement
     * has none: it uses the Speed effect's icon instead (see {@link #drawSkillIcon}).
     */
    private static final ItemStack[] SKILL_ICONS = {
        new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.SHIELD),
        new ItemStack(Items.DIRT), ItemStack.EMPTY, new ItemStack(Items.BUCKET)
    };

    private static final int PANEL_BG = 0xB80E0F12;
    private static final int SLOT_BG = 0xFF1B1C20;
    private static final int SLOT_LIGHT = 0xFF34363C;
    private static final int SLOT_DARK = 0xFF0A0B0D;
    private static final int MAIN_HAND = 0xFFC9A24A;
    private static final int BAR_TRACK = 0xFF2A2C31;
    private static final int BAR_FILL = 0xFFC9A24A;
    private static final int NAME = 0xFFFFFFFF;
    private static final int LABEL = 0xFF9A8F74;
    private static final int CLASS = 0xFFE0B56A;
    private static final int WORD = 0xFFE6D6B0;

    private static final ResourceLocation ARMOR_FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");
    private static final ResourceLocation ARMOR_HALF = ResourceLocation.withDefaultNamespace("hud/armor_half");
    private static final ResourceLocation ARMOR_EMPTY = ResourceLocation.withDefaultNamespace("hud/armor_empty");
    private static final int ARMOR_ICONS = 10;
    private static final int ARMOR_ICON = 9;
    private static final int ARMOR_STEP = 8;

    private record SlotHit(int x, int y, ItemStack stack) {
        boolean has(double mx, double my) {
            return !stack.isEmpty() && mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
        }
    }

    private record SkillHit(int x, int y, EchoPersonality.Skill skill, int value) {
        boolean has(double mx, double my) {
            return mx >= x && mx < x + SKILL_ICON && my >= y && my < y + SKILL_H;
        }
    }

    /** TEMP: live preview override, set by the Gate 2 harness. Removed before merge. */
    public static volatile EchoPersonality.Kind TEMP_PREVIEW_KIND;

    private final LiveDeathChrome chrome;
    private final DeathPortraitRenderer portrait = new DeathPortraitRenderer();
    private final List<SlotHit> slotHits = new ArrayList<>();
    private final List<SkillHit> skillHits = new ArrayList<>();
    private int armourX, armourY, armourW;
    private int armourPoints;

    public EchoPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /**
     * Draw the panel with its top at {@code y}, centred on {@code cx}; returns the y below it.
     * {@code depth} is the fall page's carriage count; the card grows to fill the room down to
     * {@code maxY} (where the footer begins) and up to {@code width} across.
     */
    public int draw(GuiGraphics g, EchoSummary echo, int depth, int cx, int y, int maxY, int width,
                    boolean settled, float partialTick) {
        Font font = chrome.font();
        slotHits.clear();
        skillHits.clear();
        armourW = 0;
        Component personalityLabel = Component.translatable("gui.dungeontrain.death.echo.personality");
        Component carriageLabel = Component.translatable("gui.dungeontrain.death.narr.lbl_carriage").append(": ");
        int firstRowW = font.width(personalityLabel) + 4
                + font.width(Component.translatable(echo.personality().langKey())) + 12
                + font.width(carriageLabel) + font.width(Integer.toString(depth));
        Layout l = Layout.fit(maxY - y, width, firstRowW);
        int pad = l.pad();
        int panelW = l.panelW();
        int panelH = l.panelH();
        int x0 = cx - panelW / 2;
        int top = y + pad;
        int topH = l.contentH();

        // A plain dark backing, no frame: only the portrait window and the slots are outlined.
        g.fill(x0, y, x0 + panelW, y + panelH, chrome.fade(PANEL_BG));

        // ---- Armour column with the offhand under it, vertically centred on the portrait.
        int colX = x0 + EDGE;
        int columnH = 5 * SLOT + 3 * GAP + OFFHAND_GAP;
        int colY = top + (topH - columnH) / 2;
        ItemStack[] worn = {
            echo.appearance().head(), echo.appearance().chest(), echo.appearance().legs(), echo.appearance().feet()
        };
        for (int i = 0; i < worn.length; i++) {
            slot(g, colX, colY + i * (SLOT + GAP), worn[i], settled, false);
        }
        slot(g, colX, colY + 4 * SLOT + 3 * GAP + OFFHAND_GAP, echo.appearance().offHand(), settled, false);

        // ---- The echo itself, standing on the card (no window), the card's full height, with its
        // name floating above its head like an in-game name tag.
        int portX = colX + SLOT + EDGE;
        int portW = l.portraitW();
        int feetY = top + topH - 4;
        if (settled && portrait.available()) {
            g.enableScissor(portX, top, portX + portW, top + topH);
            // The echo acts out its personality, at the tempo of its reaction speed.
            portrait.renderEcho(g, echo.appearance(), TEMP_PREVIEW_KIND != null ? TEMP_PREVIEW_KIND : echo.personality(), echo.reactionSpeed(),
                    portX + portW / 2, feetY, l.portraitSize());
            g.disableScissor();
        }
        drawNameTag(g, font, echoName(), portX + portW / 2, top + 2);

        // ---- Right column: personality, with the carriage number at the right edge.
        int tx = portX + portW + EDGE;
        int ty = top + 2;
        Component label = Component.translatable("gui.dungeontrain.death.echo.personality");
        g.drawString(font, label, tx, ty, chrome.fade(LABEL), false);
        g.drawString(font, Component.translatable(echo.personality().langKey()),
                tx + font.width(label) + 4, ty, chrome.fade(CLASS), false);
        drawCarriage(g, font, depth, tx + l.textW(), ty);
        ty += BASE_STEP + l.step();

        // ---- The three trait bars, then the reaction skill icons.
        ty = traitBar(g, font, EchoPersonality.Trait.FIGHT_FLIGHT, echo.fightFlight(), tx, ty, l);
        ty = traitBar(g, font, EchoPersonality.Trait.FRIENDLINESS, echo.friendliness(), tx, ty, l);
        ty = traitBar(g, font, EchoPersonality.Trait.REACTION, echo.reactionSpeed(), tx, ty, l);
        skillRow(g, font, echo, tx, ty + l.step(), settled);

        // ---- Along the bottom: the armour bar over the hotbar (main hand, then the backpack).
        int hotbarY = top + topH - SLOT;
        int barY = hotbarY - ARMOR_ICON - 2;
        armourPoints = echo.armour();
        armourX = tx;
        armourY = barY;
        armourW = (ARMOR_ICONS - 1) * ARMOR_STEP + ARMOR_ICON;
        if (settled) {
            drawArmourBar(g, tx, barY, armourPoints);
        }
        slot(g, tx, hotbarY, echo.appearance().mainHand(), settled, true);
        for (int i = 0; i < EchoSummary.MAX_CARRIED; i++) {
            ItemStack stack = i < echo.carried().size() ? echo.carried().get(i) : ItemStack.EMPTY;
            slot(g, tx + (i + 1) * (SLOT + GAP), hotbarY, stack, settled, false);
        }
        return y + panelH;
    }

    /** Hover tooltips: an item's own tooltip, or the armour bar's number. Call once settled. */
    public void drawTooltips(GuiGraphics g, int mouseX, int mouseY) {
        Font font = chrome.font();
        for (SlotHit hit : slotHits) {
            if (hit.has(mouseX, mouseY)) {
                g.renderTooltip(font, Screen.getTooltipFromItem(Minecraft.getInstance(), hit.stack()),
                        hit.stack().getTooltipImage(), mouseX, mouseY);
                return;
            }
        }
        for (SkillHit hit : skillHits) {
            if (hit.has(mouseX, mouseY)) {
                g.renderTooltip(font, Component.translatable("gui.dungeontrain.death.echo.skill_tip",
                        Component.translatable(hit.skill().langKey()), hit.value()), mouseX, mouseY);
                return;
            }
        }
        if (armourW > 0 && mouseX >= armourX && mouseX < armourX + armourW
                && mouseY >= armourY && mouseY < armourY + ARMOR_ICON) {
            g.renderTooltip(font, Component.translatable("gui.dungeontrain.death.echo.armour_tip", armourPoints),
                    mouseX, mouseY);
        }
    }

    /** Drop the cached portrait entity. Call from the screen's {@code removed()}. */
    public void clear() {
        portrait.clear();
    }

    /** "Echo of <you>", the name the echo wears in the world. */
    private static Component echoName() {
        return Component.translatable("echo.dungeontrain.mob_name", Minecraft.getInstance().getUser().getName());
    }

    /** The echo's name as an in-game name tag: white text on a quarter-black strip, centred on {@code cx}. */
    private void drawNameTag(GuiGraphics g, Font font, Component name, int cx, int y) {
        int w = font.width(name);
        int x = cx - w / 2;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        g.fill(x - 2, y - 1, x + w + 1, y + 9, chrome.fade(NAME_TAG_BG));
        g.drawString(font, name, x, y, chrome.fade(NAME), false);
        g.pose().popPose();
    }

    /** "carriage: #", right-aligned to {@code right}, using the fall page's own "carriage" label. */
    private void drawCarriage(GuiGraphics g, Font font, int depth, int right, int y) {
        Component label = Component.translatable("gui.dungeontrain.death.narr.lbl_carriage").append(": ");
        String number = Integer.toString(depth);
        int x = right - font.width(number);
        g.drawString(font, number, x, y, chrome.fade(NAME), false);
        g.drawString(font, label, x - font.width(label), y, chrome.fade(LABEL), false);
    }

    private int traitBar(GuiGraphics g, Font font, EchoPersonality.Trait trait, int value, int x, int y, Layout l) {
        // "trait name: Value", left-justified: the label muted, its value bright.
        Component label = Component.translatable(trait.labelKey()).append(": ");
        Component word = Component.translatable(EchoPersonality.wordKey(trait, value));
        g.drawString(font, label, x, y, chrome.fade(LABEL), false);
        g.drawString(font, word, x + font.width(label), y, chrome.fade(WORD), false);
        int textW = l.textW();
        int by = y + BAR_LABEL;
        g.fill(x, by, x + textW, by + l.barH(), chrome.fade(BAR_TRACK));
        int fillW = Math.round(textW * EchoPersonality.clamp(value) / (float) EchoPersonality.MAX);
        if (fillW > 0) {
            g.fill(x, by, x + fillW, by + l.barH(), chrome.fade(BAR_FILL));
        }
        return by + l.barH() + BASE_BAR_GAP + l.step();
    }

    /**
     * One slot per reaction skill: its item, with the skill's 0–10 score where a stack count sits.
     * A skill the player never showed scores 0 and its number is dimmed.
     */
    private void skillRow(GuiGraphics g, Font font, EchoSummary echo, int x, int y, boolean settled) {
        EchoPersonality.Skill[] skills = EchoPersonality.Skill.values();
        int barY = y + SKILL_ICON + SKILL_BAR_GAP;
        for (int i = 0; i < skills.length; i++) {
            int sx = x + i * SKILL_STEP;
            int value = echo.skill(skills[i]);
            if (settled) {
                drawSkillIcon(g, skills[i], sx, y);
            }
            g.fill(sx, barY, sx + SKILL_ICON, barY + SKILL_BAR_H, chrome.fade(BAR_TRACK));
            int fillW = Math.round(SKILL_ICON * EchoPersonality.clamp(value) / (float) EchoPersonality.MAX);
            if (fillW > 0) {
                g.fill(sx, barY, sx + fillW, barY + SKILL_BAR_H, chrome.fade(BAR_FILL));
            }
            skillHits.add(new SkillHit(sx, y, skills[i], value));
        }
    }

    /** A skill's icon: its item, or for movement the Speed effect's icon, both 16 px. */
    private static void drawSkillIcon(GuiGraphics g, EchoPersonality.Skill skill, int x, int y) {
        if (skill == EchoPersonality.Skill.MOVEMENT) {
            TextureAtlasSprite speed = Minecraft.getInstance().getMobEffectTextures().get(MobEffects.MOVEMENT_SPEED);
            g.blit(x - 1, y - 1, 0, 18, 18, speed);
            return;
        }
        g.renderItem(SKILL_ICONS[skill.ordinal()], x, y);
    }

    /** The HUD armour bar ({@code Gui.renderArmor}): 10 icons, 2 points each, half icons for odd points. */
    private static void drawArmourBar(GuiGraphics g, int x, int y, int points) {
        for (int i = 0; i < ARMOR_ICONS; i++) {
            int ix = x + i * ARMOR_STEP;
            int at = i * 2 + 1;
            ResourceLocation sprite = at < points ? ARMOR_FULL : at == points ? ARMOR_HALF : ARMOR_EMPTY;
            g.blitSprite(sprite, ix, y, ARMOR_ICON, ARMOR_ICON);
        }
    }

    private void slot(GuiGraphics g, int x, int y, ItemStack stack, boolean settled, boolean highlight) {
        slotFrame(g, x, y, highlight);
        if (!stack.isEmpty() && settled) {
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(chrome.font(), stack, x + 1, y + 1);
        }
        slotHits.add(new SlotHit(x, y, stack));
    }

    private void slotFrame(GuiGraphics g, int x, int y, boolean highlight) {
        g.fill(x, y, x + SLOT, y + SLOT, chrome.fade(SLOT_BG));
        g.fill(x, y, x + SLOT, y + 1, chrome.fade(highlight ? MAIN_HAND : SLOT_DARK));
        g.fill(x, y, x + 1, y + SLOT, chrome.fade(highlight ? MAIN_HAND : SLOT_DARK));
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, chrome.fade(highlight ? MAIN_HAND : SLOT_LIGHT));
        g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, chrome.fade(highlight ? MAIN_HAND : SLOT_LIGHT));
    }

    private void outline(GuiGraphics g, int x, int y, int w, int h, int argb) {
        int c = chrome.fade(argb);
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }
}
