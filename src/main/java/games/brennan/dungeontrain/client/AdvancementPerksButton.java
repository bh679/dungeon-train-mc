package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A cosmic apple icon on the advancements screen, on the Advancement Points badge's row just left of
 * it ({@link AdvancementPointsBadge#row}), opening {@link PerksScreen}; hovering names it "Perks".
 * Re-placed every frame, since Better Advancements only reports its window while drawing; hidden
 * until it has.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementPerksButton {

    private static final Component LABEL = Component.translatable("gui.dungeontrain.perks.title");
    /** Item icons are 16 px; the box is a pixel of border around one. */
    private static final int SIZE = 18;
    private static final int BG = 0xE0101018;
    private static final int BORDER = 0xFFA0A0B0;
    private static final int BORDER_HOVER = 0xFFFFFFFF;

    private static final Map<Screen, Button> BUTTONS = new WeakHashMap<>();

    private AdvancementPerksButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!DefaultAdvancementsTab.isAdvancementsScreen(screen)) return;
        Button button = new AppleButton();
        button.setTooltip(Tooltip.create(LABEL));
        button.visible = false;
        event.addListener(button);
        BUTTONS.put(screen, button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        Button button = BUTTONS.get(screen);
        if (button == null) return;
        AdvancementPointsBadge.Row row = AdvancementPointsBadge.row(screen);
        button.visible = row != null;
        if (row == null) return;
        int badgeLeft = row.right() - AdvancementPointsBadge.width(screen.getMinecraft().font);
        // Centred on the badge's row, which is shorter than the icon.
        int y = row.top() - (SIZE - AdvancementPointsBadge.BOX_HEIGHT) / 2;
        button.setPosition(badgeLeft - AdvancementPointsBadge.GAP - SIZE, y);
    }

    /** The badge's dark box with a cosmic apple in it instead of a label. */
    private static final class AppleButton extends Button {

        AppleButton() {
            super(0, 0, SIZE, SIZE, LABEL, b -> PerksScreen.open(false), DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x0 = getX();
            int y0 = getY();
            g.fill(x0, y0, x0 + SIZE, y0 + SIZE, isHoveredOrFocused() ? BORDER_HOVER : BORDER);
            g.fill(x0 + 1, y0 + 1, x0 + SIZE - 1, y0 + SIZE - 1, BG);
            g.renderItem(new ItemStack(ModItems.COSMIC_APPLE.get()), x0 + 1, y0 + 1);
        }
    }
}
