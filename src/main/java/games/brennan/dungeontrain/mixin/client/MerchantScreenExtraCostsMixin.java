package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.trader.ExtraCostMenu;
import games.brennan.dungeontrain.trader.ExtraTradeCosts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the extra-cost grid on the villager trade screen ({@link ExtraTradeCosts}): when the selected
 * trade has three or more costs, vanilla's two cost slots move into a 4-wide grid with the extra
 * slots, the grid scrolls a row at a time past three rows, and the XP bar (which sits where the
 * grid's first row goes) is hidden. Trades with one or two costs look exactly like vanilla. In
 * the trade list, a row with extra costs gets a "+N" badge, and hovering it lists every cost.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenExtraCostsMixin extends AbstractContainerScreen<MerchantMenu> {

    @Unique private static final int VANILLA_SLOT_1_X = 136;
    @Unique private static final int VANILLA_SLOT_2_X = 162;
    @Unique private static final int VANILLA_SLOT_Y = 37;
    /** Where hidden slots go: far off screen, so they neither draw nor take clicks. */
    @Unique private static final int OFF_SCREEN = -10_000;
    @Unique private static final int GRID_ROWS_HEIGHT = ExtraTradeCosts.VISIBLE_ROWS * ExtraTradeCosts.PITCH;
    @Unique private static final int SCROLL_BAR_X = 181;
    @Unique private static final int SCROLL_BAR_WIDTH = 4;
    /** Vanilla's two cost-slot frames in the GUI texture, covered while the grid shows. */
    @Unique private static final int VANILLA_FRAMES_X = 135;
    @Unique private static final int VANILLA_FRAMES_WIDTH = 44;
    @Unique private static final int GUI_GREY = 0xFFC6C6C6;
    @Unique private static final int SLOT_DARK = 0xFF373737;
    @Unique private static final int SLOT_LIGHT = 0xFFFFFFFF;
    @Unique private static final int SLOT_FILL = 0xFF8B8B8B;
    @Unique private static final int BAR_TRACK = 0xFF555555;

    @Shadow int scrollOff;

    protected MerchantScreenExtraCostsMixin(MerchantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Unique
    private ExtraCostMenu dungeontrain$menu() {
        return (ExtraCostMenu) this.menu;
    }

    /** Every cost of the selected trade when it has extra costs, else empty (vanilla layout). */
    @Unique
    private List<ItemCost> dungeontrain$gridCosts() {
        MerchantOffer offer = dungeontrain$menu().dungeontrain$selectedOffer();
        if (offer == null || ExtraTradeCosts.of(offer).isEmpty()) return List.of();
        return ExtraTradeCosts.allCosts(offer);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void dungeontrain$placeCostSlots(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        boolean grid = !dungeontrain$gridCosts().isEmpty();
        boolean firstRow = dungeontrain$menu().dungeontrain$scrollRow() == 0;
        for (int i = 0; i < 2; i++) {
            SlotPositionAccessor slot = (SlotPositionAccessor) this.menu.slots.get(i);
            if (!grid) {
                slot.dungeontrain$setX(i == 0 ? VANILLA_SLOT_1_X : VANILLA_SLOT_2_X);
                slot.dungeontrain$setY(VANILLA_SLOT_Y);
            } else if (firstRow) {
                slot.dungeontrain$setX(ExtraTradeCosts.GRID_X + i * ExtraTradeCosts.PITCH);
                slot.dungeontrain$setY(ExtraTradeCosts.GRID_Y);
            } else {
                slot.dungeontrain$setX(OFF_SCREEN);
                slot.dungeontrain$setY(OFF_SCREEN);
            }
        }
    }

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void dungeontrain$drawGrid(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        List<ItemCost> costs = dungeontrain$gridCosts();
        if (costs.isEmpty()) return;
        int left = this.leftPos;
        int top = this.topPos;
        graphics.fill(left + VANILLA_FRAMES_X, top + VANILLA_SLOT_Y - 1,
            left + VANILLA_FRAMES_X + VANILLA_FRAMES_WIDTH, top + VANILLA_SLOT_Y + 17, GUI_GREY);
        int row = dungeontrain$menu().dungeontrain$scrollRow();
        int first = row * ExtraTradeCosts.COLUMNS;
        int last = Math.min(costs.size(), first + ExtraTradeCosts.VISIBLE_SLOTS);
        for (int place = first; place < last; place++) {
            int visible = place - first;
            int x = left + ExtraTradeCosts.GRID_X - 1 + (visible % ExtraTradeCosts.COLUMNS) * ExtraTradeCosts.PITCH;
            int y = top + ExtraTradeCosts.GRID_Y - 1 + (visible / ExtraTradeCosts.COLUMNS) * ExtraTradeCosts.PITCH;
            graphics.fill(x, y, x + 18, y + 18, SLOT_DARK);
            graphics.fill(x + 1, y + 1, x + 18, y + 18, SLOT_LIGHT);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, SLOT_FILL);
        }
        int maxRow = ExtraTradeCosts.maxScrollRow(costs.size());
        if (maxRow > 0) {
            int barTop = top + ExtraTradeCosts.GRID_Y - 1;
            int x = left + SCROLL_BAR_X;
            graphics.fill(x, barTop, x + SCROLL_BAR_WIDTH, barTop + GRID_ROWS_HEIGHT, BAR_TRACK);
            int rows = maxRow + ExtraTradeCosts.VISIBLE_ROWS;
            int thumb = Math.max(6, GRID_ROWS_HEIGHT * ExtraTradeCosts.VISIBLE_ROWS / rows);
            int thumbTop = barTop + (GRID_ROWS_HEIGHT - thumb) * row / maxRow;
            graphics.fill(x, thumbTop, x + SCROLL_BAR_WIDTH, thumbTop + thumb, GUI_GREY);
        }
    }

    /** The XP bar sits where the grid's first row goes; hide it while the grid shows. */
    @Inject(method = "renderProgressBar", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$hideXpBarUnderGrid(GuiGraphics graphics, int posX, int posY, MerchantOffer offer, CallbackInfo ci) {
        if (!dungeontrain$gridCosts().isEmpty()) ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void dungeontrain$drawListBadges(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        MerchantOffers offers = this.menu.getOffers();
        int start = offers.size() > 7 ? scrollOff : 0;
        for (int row = 0; row < 7 && start + row < offers.size(); row++) {
            MerchantOffer offer = offers.get(start + row);
            int extras = ExtraTradeCosts.of(offer).size();
            if (extras == 0) continue;
            int x = this.leftPos + 5 + 35;
            int y = this.topPos + 16 + 1 + 2 + row * 20;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 300);
            graphics.drawString(this.font, "+" + extras, x + 9, y - 1, 0xFFFF55, true);
            graphics.pose().popPose();
            if (mouseX >= x - 5 && mouseX < x + 15 && mouseY >= y - 2 && mouseY < y + 18) {
                List<Component> lines = new ArrayList<>();
                for (ItemCost cost : ExtraTradeCosts.allCosts(offer)) {
                    lines.add(Component.literal(cost.count() + " × ").append(cost.itemStack().getHoverName()));
                }
                graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            }
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$scrollGrid(double mouseX, double mouseY, double scrollX, double scrollY,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!dungeontrain$overGrid(mouseX, mouseY) || scrollY == 0) return;
        dungeontrain$scrollTo(dungeontrain$menu().dungeontrain$scrollRow() + (scrollY > 0 ? -1 : 1));
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$clickScrollBar(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        List<ItemCost> costs = dungeontrain$gridCosts();
        if (costs.isEmpty() || ExtraTradeCosts.maxScrollRow(costs.size()) == 0) return;
        double x = mouseX - this.leftPos;
        double y = mouseY - this.topPos;
        int barTop = ExtraTradeCosts.GRID_Y - 1;
        if (x < SCROLL_BAR_X || x >= SCROLL_BAR_X + SCROLL_BAR_WIDTH || y < barTop || y >= barTop + GRID_ROWS_HEIGHT) return;
        int row = dungeontrain$menu().dungeontrain$scrollRow();
        dungeontrain$scrollTo(y < barTop + GRID_ROWS_HEIGHT / 2.0 ? row - 1 : row + 1);
        cir.setReturnValue(true);
    }

    @Unique
    private boolean dungeontrain$overGrid(double mouseX, double mouseY) {
        List<ItemCost> costs = dungeontrain$gridCosts();
        if (costs.isEmpty() || ExtraTradeCosts.maxScrollRow(costs.size()) == 0) return false;
        double x = mouseX - this.leftPos;
        double y = mouseY - this.topPos;
        return x >= ExtraTradeCosts.GRID_X - 1 && x < SCROLL_BAR_X + SCROLL_BAR_WIDTH
            && y >= ExtraTradeCosts.GRID_Y - 1 && y < ExtraTradeCosts.GRID_Y - 1 + GRID_ROWS_HEIGHT;
    }

    /** Scrolls here and tells the server, which shows the same backing slots in the grid. */
    @Unique
    private void dungeontrain$scrollTo(int row) {
        ExtraCostMenu menu = dungeontrain$menu();
        int before = menu.dungeontrain$scrollRow();
        menu.dungeontrain$setScrollRow(row);
        int after = menu.dungeontrain$scrollRow();
        if (after != before && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, ExtraTradeCosts.SCROLL_BUTTON_BASE + after);
        }
    }
}
