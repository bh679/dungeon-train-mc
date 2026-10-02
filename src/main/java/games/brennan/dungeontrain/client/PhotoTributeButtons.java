package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotoTributePacket;
import io.github.mortuusars.exposure.client.gui.screen.PhotographScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds two buttons under a found player photo while it is open in Exposure's viewer:
 * {@code Tribute 1 ◆} — pay a diamond to keep the photo travelling — and {@code Close}.
 * Each button is only as wide as what is written on it.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PhotoTributeButtons {

    private static final int HEIGHT = 20;
    private static final int PADDING = 6;
    private static final int GAP = 6;
    private static final int BOTTOM_MARGIN = 8;
    private static final int ICON_SIZE = 16;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int CAN_AFFORD_COLOUR = 0x55AAFF;
    private static final int CANNOT_AFFORD_COLOUR = 0xFF5555;

    /** The viewer the buttons were last added to, and those buttons. Render thread only. */
    private static Screen shownOn;
    private static List<Button> shown = List.of();

    private PhotoTributeButtons() {}

    private static boolean holdsFoundPhoto(LocalPlayer player) {
        return SharedPhotos.sharedId(player.getMainHandItem()) > 0 || SharedPhotos.sharedId(player.getOffhandItem()) > 0;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PhotographScreen screen)) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !holdsFoundPhoto(player)) return;

        Font font = minecraft.font;
        int diamonds = player.getInventory().countItem(Items.DIAMOND);
        boolean canAfford = player.getAbilities().instabuild || diamonds >= SharedPhotos.TRIBUTE_COST;
        Component closeLabel = Component.translatable("gui.dungeontrain.photo_tribute.close");
        int tributeWidth = TributeButton.widthFor(font);
        int closeWidth = font.width(closeLabel) + 2 * PADDING;
        int left = (screen.width - tributeWidth - GAP - closeWidth) / 2;
        int y = screen.height - HEIGHT - BOTTOM_MARGIN;

        TributeButton tribute = new TributeButton(left, y, tributeWidth, canAfford, button -> {
            DungeonTrainNet.sendToServer(new PhotoTributePacket());
            screen.onClose();
        });
        tribute.active = canAfford;
        tribute.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.photo_tribute.have", diamonds)));
        Button close = Button.builder(closeLabel, button -> screen.onClose())
            .bounds(left + tributeWidth + GAP, y, closeWidth, HEIGHT).build();
        event.addListener(tribute);
        event.addListener(close);
        shownOn = screen;
        shown = List.of(tribute, close);
    }

    /** Exposure's viewer draws only the photo, never its widgets, so the buttons are drawn here. */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() != shownOn) return;
        for (Button button : shown) {
            button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() != shownOn) return;
        shownOn = null;
        shown = List.of();
    }

    /** {@code Tribute <cost> <diamond>}: the cost is blue when the player can pay it, red when not. */
    private static final class TributeButton extends Button {

        private static final Component LABEL = Component.translatable("gui.dungeontrain.photo_tribute.tribute");
        private static final String COST = String.valueOf(SharedPhotos.TRIBUTE_COST);
        private static final ItemStack ICON = new ItemStack(Items.DIAMOND);

        private final boolean canAfford;

        TributeButton(int x, int y, int width, boolean canAfford, OnPress onPress) {
            super(x, y, width, HEIGHT, Component.empty(), onPress, DEFAULT_NARRATION);
            this.canAfford = canAfford;
        }

        static int widthFor(Font font) {
            return PADDING + font.width(LABEL) + font.width(" " + COST) + 2 + ICON_SIZE + PADDING / 2;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            Font font = Minecraft.getInstance().font;
            int textY = getY() + (height - font.lineHeight) / 2 + 1;
            int x = getX() + PADDING;
            graphics.drawString(font, LABEL, x, textY, LABEL_COLOUR);
            x += font.width(LABEL);
            graphics.drawString(font, " " + COST, x, textY, canAfford ? CAN_AFFORD_COLOUR : CANNOT_AFFORD_COLOUR);
            x += font.width(" " + COST) + 2;
            graphics.renderItem(ICON, x, getY() + (height - ICON_SIZE) / 2);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.translatable("gui.narrate.button", Component.empty().append(LABEL).append(" " + COST));
        }
    }
}
