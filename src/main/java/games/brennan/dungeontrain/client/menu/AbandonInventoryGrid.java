package games.brennan.dungeontrain.client.menu;

import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.storage.BackpackData;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the player would lose, for {@link AbandonConfirmScreen}: every stack they carry (armour,
 * offhand, inventory and unlocked Edible Backpack slots), empty slots left out, most valuable
 * first. Worth is Trade Everything's price for one item ({@link TradeEverythingApi}, which
 * carries DT's own providers: gear stats, enchantments, totems, the community values) times the
 * stack size; stacks it can't price follow, biggest first, and ties keep inventory order. Prices
 * TE derives from recipes are indexed on the server, so in singleplayer they count and on a
 * multiplayer client the order rests on the fixed prices alone.
 *
 * <p>Laid out in rows of a fixed width. When the rows the screen can spare can't hold them all,
 * the last slot reads "+N" for the rest.</p>
 */
@OnlyIn(Dist.CLIENT)
final class AbandonInventoryGrid {

    static final int SLOT = 18;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean warned;

    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    /** Above the item (z 150) and its count (z 200). */
    private static final float FIRE_Z = 300F;
    private static final ResourceLocation FIRE = ResourceLocation.withDefaultNamespace("block/fire_0");
    private static final float TEXT_Z = 200F;

    private final List<ItemStack> stacks;
    private int cols = 1;
    private int shown;
    private int x;
    private int y;

    private AbandonInventoryGrid(List<ItemStack> stacks) {
        this.stacks = stacks;
        this.shown = stacks.size();
    }

    /** @return null when the player carries nothing (or there is no player) */
    static AbandonInventoryGrid of(Player player) {
        if (player == null) return null;
        List<ItemStack> all = carried(player);
        if (all.isEmpty()) return null;
        all.sort(Comparator.comparingLong(AbandonInventoryGrid::worth).reversed()
                .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));
        return new AbandonInventoryGrid(all);
    }

    private static List<ItemStack> carried(Player player) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) out.add(s);
        }
        BackpackData pack = player.getData(ModAttachments.BACKPACK);
        ItemStackHandler items = pack.items();
        int unlocked = Math.max(0, Math.min(pack.unlocked(), items.getSlots()));
        for (int i = 0; i < unlocked; i++) {
            ItemStack s = items.getStackInSlot(i);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Price of one × count; -1 for an item with no price, so it sorts after every priced one. */
    private static long worth(ItemStack stack) {
        try {
            int each = TradeEverythingApi.getValueSixteenths(stack.copyWithCount(1));
            return each > 0 ? (long) each * stack.getCount() : -1L;
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[abandon] could not price {} for sorting; unpriced items sort by count", stack, e);
            }
            return -1L;
        }
    }

    /** Rows of {@code columns}, at most {@code maxRows}; returns false if not even one row fits. */
    boolean layout(int columns, int maxRows) {
        if (columns < 1 || maxRows < 1) return false;
        this.cols = Math.min(columns, stacks.size());
        this.shown = Math.min(stacks.size(), cols * maxRows);
        return true;
    }

    int width() { return cols * SLOT; }

    int height() { return ((shown + cols - 1) / cols) * SLOT; }

    void place(int x, int y) {
        this.x = x;
        this.y = y;
    }

    private boolean overflowing() { return shown < stacks.size(); }

    private int slotX(int i) { return x + (i % cols) * SLOT; }

    private int slotY(int i) { return y + (i / cols) * SLOT; }

    /** How the carried items react while "Step off and die" is hovered. */
    enum HoverEffect { RED, FIRE }

    /** Chosen effect; package-visible so both can be compared before one is settled on. */
    static HoverEffect hoverEffect = HoverEffect.RED;

    /** @param fade 0 normal; towards 1 the items themselves react (the slots stay as they are) */
    void render(GuiGraphics g, Font font, float fade) {
        float k = Math.max(0F, Math.min(1F, fade));
        for (int i = 0; i < shown; i++) {
            int sx = slotX(i);
            int sy = slotY(i);
            drawSlot(g, sx, sy);
            if (overflowing() && i == shown - 1) {
                drawMore(g, font, sx, sy, stacks.size() - shown + 1);
                continue;
            }
            ItemStack stack = stacks.get(i);
            drawItem(g, stack, sx + 1, sy + 1, hoverEffect == HoverEffect.RED ? k : 0F);
            g.renderItemDecorations(font, stack, sx + 1, sy + 1);
            if (hoverEffect == HoverEffect.FIRE && k > 0F) drawFire(g, sx + 1, sy + 1, Math.min(1F, 2F * k));   // fire burns at full strength
        }
    }

    /**
     * The item with its green and blue pulled down by {@code red}, so only its own pixels redden.
     * {@link GuiGraphics#renderItem} flushes before returning, so the shader colour is undone in time.
     */
    private static void drawItem(GuiGraphics g, ItemStack stack, int x, int y, float red) {
        if (red <= 0F) {
            g.renderItem(stack, x, y);
            return;
        }
        float gb = 1F - red;
        RenderSystem.setShaderColor(1F, gb, gb, 1F);
        try {
            g.renderItem(stack, x, y);
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }

    /** Vanilla's animated fire (block atlas {@code block/fire_0}) over the item, fading in with it. */
    private static void drawFire(GuiGraphics g, int x, int y, float alpha) {
        TextureAtlasSprite fire = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(FIRE);
        RenderSystem.enableBlend();
        g.blit(x, y, (int) FIRE_Z, 16, 16, fire, 1F, 1F, 1F, alpha);
        RenderSystem.disableBlend();
    }

    private static void drawMore(GuiGraphics g, Font font, int sx, int sy, int more) {
        String label = "+" + more;
        g.pose().pushPose();
        g.pose().translate(0, 0, TEXT_Z);
        g.drawString(font, label, sx + (SLOT - font.width(label)) / 2, sy + 5, 0xFFFFFF);
        g.pose().popPose();
    }

    /** The vanilla item tooltip for the slot under the mouse. */
    void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        for (int i = 0; i < shown; i++) {
            if (overflowing() && i == shown - 1) continue;
            int sx = slotX(i);
            int sy = slotY(i);
            if (mouseX >= sx && mouseY >= sy && mouseX < sx + SLOT && mouseY < sy + SLOT) {
                g.renderTooltip(font, stacks.get(i), mouseX, mouseY);
                return;
            }
        }
    }

    private static void drawSlot(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + SLOT, sy + SLOT, SLOT_BG);
        g.fill(sx, sy, sx + SLOT, sy + 1, SLOT_DARK);
        g.fill(sx, sy, sx + 1, sy + SLOT, SLOT_DARK);
        g.fill(sx, sy + SLOT - 1, sx + SLOT, sy + SLOT, SLOT_LIGHT);
        g.fill(sx + SLOT - 1, sy, sx + SLOT, sy + SLOT, SLOT_LIGHT);
    }
}
