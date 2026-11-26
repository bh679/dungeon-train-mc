package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.alchemist.AlchemistPerks;
import games.brennan.dungeontrain.perks.effects.alchemist.PocketBrewOpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Standless Brewing's Brew button on the survival inventory: a brewing stand icon just right of the recipe
 * book button, "Brew" on hover. It follows the window when the recipe book moves it, and shows only while
 * the player has the perk. The server checks the perk again before opening the stand.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PocketBrewButton {

    public static final String KEY_LABEL = "gui.dungeontrain.pocket_brewing.button";
    /** Vanilla's recipe book button sits at x 104, y 61 of the window, 20 by 18; this one sits beside it. */
    static final int X = 126;
    static final int Y = 61;
    static final int WIDTH = 20;
    static final int HEIGHT = 18;

    private PocketBrewButton() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) return;
        event.addListener(new BrewButton(screen));
    }

    private static final class BrewButton extends Button {
        private static final ItemStack ICON = new ItemStack(Items.BREWING_STAND);
        private final InventoryScreen screen;

        BrewButton(InventoryScreen screen) {
            super(screen.getGuiLeft() + X, screen.getGuiTop() + Y, WIDTH, HEIGHT, Component.translatable(KEY_LABEL),
                b -> PacketDistributor.sendToServer(new PocketBrewOpenPacket()), DEFAULT_NARRATION);
            this.screen = screen;
            setTooltip(Tooltip.create(Component.translatable(KEY_LABEL)));
        }

        /** Shown only with the perk; placed every frame, since the recipe book moves the window. */
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            visible = mc.player != null && Perks.rank(mc.player, AlchemistPerks.STANDLESS_BREWING) > 0;
            setPosition(screen.getGuiLeft() + X, screen.getGuiTop() + Y);
            super.render(g, mouseX, mouseY, partialTick);
        }

        /** The button's frame without its label, then the brewing stand icon. */
        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Component label = getMessage();
            setMessage(Component.empty());
            super.renderWidget(g, mouseX, mouseY, partialTick);
            setMessage(label);
            g.renderItem(ICON, getX() + (WIDTH - 16) / 2, getY() + (HEIGHT - 16) / 2);
        }
    }
}
