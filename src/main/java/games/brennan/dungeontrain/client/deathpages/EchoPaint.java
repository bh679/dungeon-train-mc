package games.brennan.dungeontrain.client.deathpages;

import games.brennan.dungeontrain.client.live.deathpages.LiveDeathChrome;
import games.brennan.dungeontrain.echo.EchoPersonality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Colours, sizes and small drawing helpers shared by the echo page and its two tabs. */
final class EchoPaint {

    static final int SLOT = 18;
    static final int GAP = 2;

    static final int SLOT_BG = 0xFF1B1C20;
    static final int SLOT_LIGHT = 0xFF34363C;
    static final int SLOT_DARK = 0xFF0A0B0D;
    static final int SELECTED = 0xFFC9A24A;
    static final int TRACK = 0xFF2A2C31;
    static final int FILL = 0xFFC9A24A;
    static final int NAME = 0xFFFFFFFF;
    static final int LABEL = 0xFF9A8F74;
    static final int CLASS = 0xFFE0B56A;
    static final int WORD = 0xFFE6D6B0;
    static final int MUTED = 0xFF6B6553;

    static final int ARMOR_ICONS = 10;
    static final int ARMOR_ICON = 9;
    static final int ARMOR_STEP = 8;
    static final int ARMOR_BAR_W = (ARMOR_ICONS - 1) * ARMOR_STEP + ARMOR_ICON;
    private static final ResourceLocation ARMOR_FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");
    private static final ResourceLocation ARMOR_HALF = ResourceLocation.withDefaultNamespace("hud/armor_half");
    private static final ResourceLocation ARMOR_EMPTY = ResourceLocation.withDefaultNamespace("hud/armor_empty");

    /**
     * The item that stands for each reaction skill, in {@link EchoPersonality.Skill} order. Movement
     * has none: it uses the Speed effect's icon instead (see {@link #skillIcon}).
     */
    private static final ItemStack[] SKILL_ICONS = {
        new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.SHIELD),
        new ItemStack(Items.DIRT), ItemStack.EMPTY, new ItemStack(Items.BUCKET)
    };

    /** The item that stands for each {@link EchoPersonality.Hobby}: a book for Reading, an emerald for Trading. */
    private static final ItemStack[] HOBBY_ICONS = {
        new ItemStack(Items.BOOK), new ItemStack(Items.EMERALD)
    };

    private EchoPaint() {
    }

    /** A beveled inventory slot frame; {@code selected} draws it in gold. */
    static void slotFrame(GuiGraphics g, LiveDeathChrome chrome, int x, int y, boolean selected) {
        int dark = selected ? SELECTED : SLOT_DARK;
        int light = selected ? SELECTED : SLOT_LIGHT;
        g.fill(x, y, x + SLOT, y + SLOT, chrome.fade(SLOT_BG));
        g.fill(x, y, x + SLOT, y + 1, chrome.fade(dark));
        g.fill(x, y, x + 1, y + SLOT, chrome.fade(dark));
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, chrome.fade(light));
        g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, chrome.fade(light));
    }

    /** The HUD armour bar ({@code Gui.renderArmor}): 10 icons, 2 points each, half icons for odd points. */
    static void armourBar(GuiGraphics g, int x, int y, int points) {
        for (int i = 0; i < ARMOR_ICONS; i++) {
            int at = i * 2 + 1;
            ResourceLocation sprite = at < points ? ARMOR_FULL : at == points ? ARMOR_HALF : ARMOR_EMPTY;
            g.blitSprite(sprite, x + i * ARMOR_STEP, y, ARMOR_ICON, ARMOR_ICON);
        }
    }

    /** A skill's 16 px icon with its top-left at ({@code x}, {@code y}): its item, or the Speed effect for movement. */
    static void skillIcon(GuiGraphics g, EchoPersonality.Skill skill, int x, int y) {
        if (skill == EchoPersonality.Skill.MOVEMENT) {
            TextureAtlasSprite speed = Minecraft.getInstance().getMobEffectTextures().get(MobEffects.MOVEMENT_SPEED);
            g.blit(x - 1, y - 1, 0, 18, 18, speed);
            return;
        }
        g.renderItem(SKILL_ICONS[skill.ordinal()], x, y);
    }

    /** Draw {@code hobby}'s icon (16×16) at ({@code x}, {@code y}). */
    static void hobbyIcon(GuiGraphics g, EchoPersonality.Hobby hobby, int x, int y) {
        g.renderItem(HOBBY_ICONS[hobby.ordinal()], x, y);
    }
}
