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

import java.util.ArrayList;
import java.util.List;

/**
 * "The echo": the death page about the echo this death left behind, laid out like the vanilla
 * inventory in the death screen's own colours. Armour column, a large animated portrait and a text
 * column (name and depth, personality, trait bars) on top; the in-game armour bar; then the offhand
 * slot and a hotbar-style row — main hand first, then what the echo carries in its backpack.
 *
 * <p>The text column ends with reaction speed: one bar for the overall score, then a row of six
 * item icons, one per PlayerMob reaction skill (melee, ranged, defence, building, movement,
 * items), each showing its 0–10 score where a stack count sits; hover names the skill.</p>
 *
 * <p>Items, sprites and the portrait can't take the page fade's alpha, so they draw only once the
 * page has settled (the screen passes {@code settled}). Owns its portrait renderer: call
 * {@link #clear()} from the screen's {@code removed()}.</p>
 */
public final class EchoPage {

    private static final int SLOT = 18;
    private static final int GAP = 2;
    private static final int PORTRAIT_W = 60;
    private static final int TEXT_W = 128;
    private static final int OFFHAND_GAP = 10;
    private static final int SKILL_STEP = SLOT + 2;

    /**
     * Vertical spacing. The roomy layout has the large portrait; the compact one fits the
     * smallest GUI (240 px tall, the dev window at auto scale) and is picked when the roomy
     * panel would run into the footer. The portrait is as tall as the text column beside it:
     * name, personality, three trait bars and the skill icon row.
     */
    private record Layout(int pad, int portraitSize, int nameStep, int classStep,
                          int barLabelStep, int barH, int barGap) {
        int textH() {
            return 2 + nameStep + classStep + 3 * (barLabelStep + barH + barGap) + SLOT;
        }

        int portraitH() {
            return textH() + 2;
        }

        int panelH() {
            return pad + portraitH() + pad + ARMOR_ICON + 2 + SLOT + pad;
        }
    }

    private static final Layout ROOMY = new Layout(6, 56, 14, 16, 10, 4, 7);
    private static final Layout COMPACT = new Layout(3, 42, 10, 10, 9, 3, 3);

    /** The item that stands for each reaction skill, in {@link EchoPersonality.Skill} order. */
    private static final ItemStack[] SKILL_ICONS = {
        new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.SHIELD),
        new ItemStack(Items.SCAFFOLDING), new ItemStack(Items.MINECART), new ItemStack(Items.CHEST)
    };
    private static final int SKILL_SCORE = 0xFFFFFFFF;
    private static final int SKILL_ZERO = 0xFF6B6553;

    private static final int PANEL_BG = 0xCC1B1A17;
    private static final int PANEL_BORDER = 0xFF4A4538;
    private static final int PORTRAIT_BG = 0xFF0E0E0D;
    private static final int SLOT_BG = 0xFF211E1A;
    private static final int SLOT_LIGHT = 0xFF3A352D;
    private static final int SLOT_DARK = 0xFF100E0B;
    private static final int MAIN_HAND = 0xFFC9A24A;
    private static final int BAR_TRACK = 0xFF2A2620;
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
            return mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
        }
    }

    private final LiveDeathChrome chrome;
    private final DeathPortraitRenderer portrait = new DeathPortraitRenderer();
    private final List<SlotHit> slotHits = new ArrayList<>();
    private final List<SkillHit> skillHits = new ArrayList<>();
    private int armourX, armourY, armourW;
    private int armourPoints;

    public EchoPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
    }

    /** Total panel width, for centring. */
    public static int panelWidth() {
        return 6 + SLOT + 6 + PORTRAIT_W + 6 + TEXT_W + 6;
    }

    /**
     * Draw the panel with its top at {@code y}, centred on {@code cx}; returns the y below it.
     * {@code depth} is the fall page's carriage count; {@code maxY} is where the footer begins,
     * which picks the roomy or compact layout.
     */
    public int draw(GuiGraphics g, EchoSummary echo, int depth, int cx, int y, int maxY,
                    boolean settled, float partialTick) {
        Font font = chrome.font();
        slotHits.clear();
        skillHits.clear();
        armourW = 0;
        Layout l = y + ROOMY.panelH() <= maxY ? ROOMY : COMPACT;
        int pad = l.pad();
        int panelW = panelWidth();
        int x0 = cx - panelW / 2;
        int topH = l.portraitH();
        int armourBarY = y + pad + topH + pad;
        int hotbarY = armourBarY + ARMOR_ICON + 2;
        int panelH = l.panelH();

        g.fill(x0, y, x0 + panelW, y + panelH, chrome.fade(PANEL_BG));
        outline(g, x0, y, panelW, panelH, PANEL_BORDER);

        // ---- Armour column, vertically centred on the portrait.
        int colX = x0 + 6;
        int armourColH = 4 * SLOT + 3 * GAP;
        int colY = y + pad + (topH - armourColH) / 2;
        ItemStack[] worn = {
            echo.appearance().head(), echo.appearance().chest(), echo.appearance().legs(), echo.appearance().feet()
        };
        for (int i = 0; i < worn.length; i++) {
            slot(g, colX, colY + i * (SLOT + GAP), worn[i], settled, false);
        }

        // ---- Portrait window.
        int portX = colX + SLOT + 6;
        int portY = y + pad;
        g.fill(portX, portY, portX + PORTRAIT_W, portY + topH, chrome.fade(PORTRAIT_BG));
        outline(g, portX, portY, PORTRAIT_W, topH, SLOT_LIGHT);
        if (settled && portrait.available()) {
            g.enableScissor(portX + 1, portY + 1, portX + PORTRAIT_W - 1, portY + topH - 1);
            portrait.render(g, echo.appearance(), 1, portX + PORTRAIT_W / 2, portY + topH - 4,
                    partialTick, l.portraitSize());
            g.disableScissor();
        }

        // ---- Text column.
        int tx = portX + PORTRAIT_W + 6;
        int ty = portY + 2;
        drawNameLine(g, font, echoName(), depth, tx, ty);
        ty += l.nameStep();
        Component label = Component.translatable("gui.dungeontrain.death.echo.personality");
        g.drawString(font, label, tx, ty, chrome.fade(LABEL), false);
        g.drawString(font, Component.translatable(echo.personality().langKey()),
                tx + font.width(label) + 4, ty, chrome.fade(CLASS), false);
        ty += l.classStep();
        ty = traitBar(g, font, EchoPersonality.Trait.FIGHT_FLIGHT, echo.fightFlight(), tx, ty, l);
        ty = traitBar(g, font, EchoPersonality.Trait.FRIENDLINESS, echo.friendliness(), tx, ty, l);
        ty = traitBar(g, font, EchoPersonality.Trait.REACTION, echo.reactionSpeed(), tx, ty, l);
        skillRow(g, font, echo, tx, ty, settled);

        // ---- Armour bar, over the hotbar's first slot like the in-game HUD.
        int hotbarX = colX + SLOT + OFFHAND_GAP;
        armourPoints = echo.armour();
        armourX = hotbarX;
        armourY = armourBarY;
        armourW = (ARMOR_ICONS - 1) * ARMOR_STEP + ARMOR_ICON;
        if (settled) {
            drawArmourBar(g, hotbarX, armourBarY, armourPoints);
        }

        // ---- Offhand, then the hotbar: main hand first, then the backpack.
        slot(g, colX, hotbarY, echo.appearance().offHand(), settled, false);
        slot(g, hotbarX, hotbarY, echo.appearance().mainHand(), settled, true);
        for (int i = 0; i < EchoSummary.MAX_CARRIED; i++) {
            ItemStack stack = i < echo.carried().size() ? echo.carried().get(i) : ItemStack.EMPTY;
            slot(g, hotbarX + (i + 1) * (SLOT + GAP), hotbarY, stack, settled, false);
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

    private void drawNameLine(GuiGraphics g, Font font, Component name, int depth, int x, int y) {
        g.drawString(font, name, x, y, chrome.fade(NAME), false);
        int sx = x + font.width(name) + 4;
        g.drawString(font, "|", sx, y, chrome.fade(LABEL), false);
        g.drawString(font, Integer.toString(depth), sx + font.width("|") + 4, y, chrome.fade(NAME), false);
    }

    private int traitBar(GuiGraphics g, Font font, EchoPersonality.Trait trait, int value, int x, int y, Layout l) {
        Component label = Component.translatable(trait.labelKey());
        Component word = Component.translatable(EchoPersonality.wordKey(trait, value));
        g.drawString(font, label, x, y, chrome.fade(LABEL), false);
        g.drawString(font, word, x + TEXT_W - font.width(word), y, chrome.fade(WORD), false);
        int by = y + l.barLabelStep();
        g.fill(x, by, x + TEXT_W, by + l.barH(), chrome.fade(BAR_TRACK));
        int fillW = Math.round(TEXT_W * EchoPersonality.clamp(value) / (float) EchoPersonality.MAX);
        if (fillW > 0) {
            g.fill(x, by, x + fillW, by + l.barH(), chrome.fade(BAR_FILL));
        }
        return by + l.barH() + l.barGap();
    }

    /**
     * One slot per reaction skill: its item, with the skill's 0–10 score where a stack count sits.
     * A skill the player never showed scores 0 and its number is dimmed.
     */
    private void skillRow(GuiGraphics g, Font font, EchoSummary echo, int x, int y, boolean settled) {
        EchoPersonality.Skill[] skills = EchoPersonality.Skill.values();
        for (int i = 0; i < skills.length; i++) {
            int sx = x + i * SKILL_STEP;
            int value = echo.skill(skills[i]);
            slotFrame(g, sx, y, false);
            if (settled) {
                g.renderItem(SKILL_ICONS[i], sx + 1, y + 1);
                String text = Integer.toString(value);
                g.pose().pushPose();
                g.pose().translate(0.0F, 0.0F, 200.0F);
                g.drawString(font, text, sx + SLOT - 1 - font.width(text), y + SLOT - 9,
                        value == 0 ? SKILL_ZERO : SKILL_SCORE, true);
                g.pose().popPose();
            }
            skillHits.add(new SkillHit(sx, y, skills[i], value));
        }
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
