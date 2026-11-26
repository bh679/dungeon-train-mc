package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.effects.alchemist.PocketBrewingMath;
import games.brennan.dungeontrain.perks.effects.alchemist.PocketBrewingMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The pocket brewing stand (Standless Brewing): vanilla's brewing stand top (fuel, bubbles, ingredient,
 * progress arrow), then the stand's bottle slots in centred rows of nine, then the player inventory. The
 * fuel count sits right of the stand; the next free fuel shares the inventory label's line. Past six rows
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
    /** Where vanilla's texture starts the inventory label, 12 above the inventory's first row. */
    private static final int TEXTURE_INVENTORY_V = 72;
    private static final int INVENTORY_PART = 166 - TEXTURE_INVENTORY_V;

    private static final int FACE = 0xFFC6C6C6;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADE = 0xFF373737;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int TEXT = 0xFF404040;

    public static final String KEY_FUEL = "gui.dungeontrain.pocket_brewing.fuel";
    public static final String KEY_FREE_FUEL = "gui.dungeontrain.pocket_brewing.free_fuel";
    public static final String KEY_PAGE = "gui.dungeontrain.pocket_brewing.page";

    public PocketBrewingScreen(PocketBrewingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PocketBrewingMenu.WIDTH;
        this.imageHeight = menu.inventoryTop() + 83;
        this.inventoryLabelY = menu.inventoryTop() - 11;
    }

    @Override
    protected void init() {
        super.init();
        titleLabelX = (imageWidth - font.width(title)) / 2;
        int pages = PocketBrewingMenu.pages(menu.bottles());
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> menu.setPage(menu.page() - 1))
                .bounds(leftPos + 4, topPos + 3, 12, 11).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> menu.setPage(menu.page() + 1))
                .bounds(leftPos + imageWidth - 16, topPos + 3, 12, 11).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        g.drawString(font, Component.translatable(KEY_FUEL, menu.fuel(), PocketBrewingMath.FUEL_MAX), 114, 40, TEXT, false);
        int seconds = menu.freeFuelSeconds();
        if (seconds >= 0) {
            Component next = Component.translatable(KEY_FREE_FUEL, clock(seconds));
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
        g.fill(x + 3, y + HEADER, x + imageWidth - 3, y + invTop - 12, FACE);
        g.fill(x, y + HEADER, x + 1, y + invTop - 12, 0xFF000000);
        g.fill(x + 1, y + HEADER, x + 3, y + invTop - 12, LIGHT);
        g.fill(x + imageWidth - 1, y + HEADER, x + imageWidth, y + invTop - 12, 0xFF000000);
        g.fill(x + imageWidth - 3, y + HEADER, x + imageWidth - 1, y + invTop - 12, 0xFF555555);
        g.blit(TEXTURE, x, y + invTop - 12, 0, TEXTURE_INVENTORY_V, imageWidth, INVENTORY_PART);

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
