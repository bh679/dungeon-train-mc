package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.effects.alchemist.PocketBrewingMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The pocket brewing stand (Standless Brewing): vanilla's brewing stand top (fuel, bubbles, ingredient,
 * progress arrow, and its fuel bar), then the stand's bottle slots in centred rows of nine, then the player
 * inventory. The next refuel shares the inventory label's line, and a chest button right of the stand goes
 * back to the inventory. Past six rows
 * of bottles, arrows beside the title page through them.
 */
@OnlyIn(Dist.CLIENT)
public final class PocketBrewingScreen extends AbstractContainerScreen<PocketBrewingMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/brewing_stand.png");
    private static final ResourceLocation FUEL_LENGTH_SPRITE = ResourceLocation.withDefaultNamespace("container/brewing_stand/fuel_length");
    private static final ResourceLocation BREW_PROGRESS_SPRITE = ResourceLocation.withDefaultNamespace("container/brewing_stand/brew_progress");
    private static final ResourceLocation BUBBLES_SPRITE = ResourceLocation.withDefaultNamespace("container/brewing_stand/bubbles");
    private static final int[] BUBBLE_LENGTHS = {29, 24, 20, 16, 11, 6, 0};

    /** The texture's stand top, down to just under the fuel bar. */
    private static final int HEADER = 48;
    /** Vanilla's texture below its bottle slots, 8 above the inventory's first row (the label is drawn, not textured). */
    private static final int TEXTURE_INVENTORY_V = 76;
    private static final int INVENTORY_TOP_OFFSET = 8;
    private static final int INVENTORY_PART = 166 - TEXTURE_INVENTORY_V;

    private static final int FACE = 0xFFC6C6C6;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADE = 0xFF373737;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int TEXT = 0xFF404040;

    public static final String KEY_REFUEL = "gui.dungeontrain.pocket_brewing.refuel";
    public static final String KEY_PAGE = "gui.dungeontrain.pocket_brewing.page";

    public PocketBrewingScreen(PocketBrewingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PocketBrewingMenu.WIDTH;
        this.imageHeight = menu.inventoryTop() + 83;
        this.inventoryLabelY = menu.inventoryTop() - 11;
    }

    /** The inventory button, the Brew button's way back: right of the stand, where vanilla's stand is empty. */
    static final int BACK_X = 150, BACK_Y = 17, BACK_W = 20, BACK_H = 18;
    private static final ItemStack BACK_ICON = new ItemStack(Items.CHEST);

    @Override
    protected void init() {
        super.init();
        titleLabelX = (imageWidth - font.width(title)) / 2;
        Component inventory = Component.translatable("container.inventory");
        Button back = new Button(leftPos + BACK_X, topPos + BACK_Y, BACK_W, BACK_H, inventory,
                b -> backToInventory(), narration -> narration.get()) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                Component label = getMessage();
                setMessage(Component.empty());
                super.renderWidget(g, mouseX, mouseY, partialTick);
                setMessage(label);
                g.renderItem(BACK_ICON, getX() + (BACK_W - 16) / 2, getY() + (BACK_H - 16) / 2);
            }
        };
        back.setTooltip(Tooltip.create(inventory));
        addRenderableWidget(back);
        int pages = PocketBrewingMenu.pages(menu.bottles());
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> menu.setPage(menu.page() - 1))
                .bounds(leftPos + 4, topPos + 3, 12, 11).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> menu.setPage(menu.page() + 1))
                .bounds(leftPos + imageWidth - 16, topPos + 3, 12, 11).build());
        }
    }

    /** Close the stand and open the player's inventory, as the Brew button opened the stand from it. */
    private void backToInventory() {
        if (minecraft == null || minecraft.player == null) return;
        minecraft.player.closeContainer();
        minecraft.setScreen(new InventoryScreen(minecraft.player));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        int seconds = menu.refuelSeconds();
        if (seconds >= 0) {
            Component next = Component.translatable(KEY_REFUEL, clock(seconds));
            g.drawString(font, next, imageWidth - 8 - font.width(next), inventoryLabelY, TEXT, false);
        }
        int pages = PocketBrewingMenu.pages(menu.bottles());
        if (pages > 1) {
            Component page = Component.translatable(KEY_PAGE, menu.page() + 1, pages);
            g.drawString(font, page, imageWidth - 20 - font.width(page), titleLabelY, TEXT, false);
        }
    }

    /** "2:41" for 161 seconds. */
    static String clock(int seconds) {
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int invTop = menu.inventoryTop();
        g.blit(TEXTURE, x, y, 0, 0, imageWidth, HEADER);
        int band = invTop - INVENTORY_TOP_OFFSET;
        g.fill(x + 3, y + HEADER, x + imageWidth - 3, y + band, FACE);
        g.fill(x, y + HEADER, x + 1, y + band, 0xFF000000);
        g.fill(x + 1, y + HEADER, x + 3, y + band, LIGHT);
        g.fill(x + imageWidth - 1, y + HEADER, x + imageWidth, y + band, 0xFF000000);
        g.fill(x + imageWidth - 3, y + HEADER, x + imageWidth - 1, y + band, 0xFF555555);
        g.blit(TEXTURE, x, y + band, 0, TEXTURE_INVENTORY_V, imageWidth, INVENTORY_PART);

        int bottles = menu.bottles();
        int first = menu.page() * PocketBrewingMenu.PAGE_SIZE;
        int last = Math.min(bottles, first + PocketBrewingMenu.PAGE_SIZE);
        for (int i = first; i < last; i++) {
            int sx = x + PocketBrewingMenu.bottleX(i, bottles) - 1;
            int sy = y + PocketBrewingMenu.bottleY(i) - 1;
            g.fill(sx, sy, sx + 18, sy + 18, LIGHT);
            g.fill(sx, sy, sx + 17, sy + 17, SHADE);
            g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_BG);
        }

        int fuel = Mth.clamp((18 * menu.fuel() + 19) / 20, 0, 18);
        if (fuel > 0) g.blitSprite(FUEL_LENGTH_SPRITE, 18, 4, 0, 0, x + 60, y + 44, fuel, 4);
        int left = menu.brewTime();
        int total = Math.max(1, menu.brewTotal());
        if (left > 0) {
            int arrow = (int) (28.0F * (1.0F - (float) left / total));
            if (arrow > 0) g.blitSprite(BREW_PROGRESS_SPRITE, 9, 28, 0, 0, x + 97, y + 16, 9, arrow);
            int bubbles = BUBBLE_LENGTHS[left / 2 % 7];
            if (bubbles > 0) g.blitSprite(BUBBLES_SPRITE, 12, 29, 0, 29 - bubbles, x + 63, y + 14 + 29 - bubbles, 12, bubbles);
        }
    }
}
