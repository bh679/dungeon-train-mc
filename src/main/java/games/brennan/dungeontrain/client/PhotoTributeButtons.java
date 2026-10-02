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
 * how many views it has left, the Tribute cost beside an emerald — pay it to keep the photo
 * travelling — and {@code X} to close. Either way the photo burns afterwards. Each button is only
 * as wide as what is drawn on it.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PhotoTributeButtons {

    private static final int HEIGHT = 20;
    private static final int PADDING = 6;
    private static final int GAP = 6;
    private static final int BOTTOM_MARGIN = 8;
    private static final int ICON_SIZE = 16;
    private static final int CAN_AFFORD_COLOUR = 0x55AAFF;
    private static final int CANNOT_AFFORD_COLOUR = 0xFF5555;
    private static final String CLOSE_MARK = "X";

    /** The viewer the buttons were last added to, and those buttons. Render thread only. */
    private static Screen shownOn;
    private static List<Button> shown = List.of();

    private PhotoTributeButtons() {}

    /** The found photo the player is holding, or an empty stack. */
    private static ItemStack heldFoundPhoto(LocalPlayer player) {
        if (SharedPhotos.sharedId(player.getMainHandItem()) > 0) return player.getMainHandItem();
        return SharedPhotos.sharedId(player.getOffhandItem()) > 0 ? player.getOffhandItem() : ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PhotographScreen screen)) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ItemStack photo = player == null ? ItemStack.EMPTY : heldFoundPhoto(player);
        if (photo.isEmpty()) return;

        Font font = minecraft.font;
        int cost = SharedPhotos.tributeCost(photo);
        int emeralds = player.getInventory().countItem(Items.EMERALD);
        boolean canAfford = emeralds >= cost;
        Component closeLabel = Component.translatable("gui.dungeontrain.photo_tribute.close");
        int viewsLeft = SharedPhotos.viewsLeft(photo);
        Component viewsLabel = Component.literal(viewsLeft + "/" + SharedPhotos.VIEWS_MAX);
        int viewsWidth = font.width(viewsLabel) + 2 * PADDING;
        int tributeWidth = TributeButton.widthFor(font, cost);
        int closeWidth = font.width(CLOSE_MARK) + 2 * PADDING;
        int left = (screen.width - viewsWidth - GAP - tributeWidth - GAP - closeWidth) / 2;
        int y = screen.height - HEIGHT - BOTTOM_MARGIN;

        Button views = Button.builder(viewsLabel, button -> { })
            .bounds(left, y, viewsWidth, HEIGHT)
            .tooltip(Tooltip.create(Component.translatable("gui.dungeontrain.photo_tribute.views_left", viewsLeft)))
            .createNarration(message -> Component.translatable("gui.dungeontrain.photo_tribute.views_left", viewsLeft)).build();
        left += viewsWidth + GAP;
        TributeButton tribute = new TributeButton(left, y, tributeWidth, cost, canAfford, button -> {
            DungeonTrainNet.sendToServer(new PhotoTributePacket());
            screen.onClose();
        });
        tribute.active = canAfford;
        tribute.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.photo_tribute.offer")
            .append("\n").append(Component.translatable("gui.dungeontrain.photo_tribute.have", emeralds))));
        Button close = Button.builder(Component.literal(CLOSE_MARK), button -> screen.onClose())
            .bounds(left + tributeWidth + GAP, y, closeWidth, HEIGHT)
            .createNarration(message -> Component.translatable("gui.narrate.button", closeLabel)).build();
        event.addListener(views);
        event.addListener(tribute);
        event.addListener(close);
        shownOn = screen;
        shown = List.of(views, tribute, close);
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

    /** {@code <cost> <emerald>}: the cost is blue when the player carries enough emeralds, red when not. */
    private static final class TributeButton extends Button {

        private static final Component LABEL = Component.translatable("gui.dungeontrain.photo_tribute.tribute");
        private static final ItemStack ICON = new ItemStack(Items.EMERALD);

        private final String cost;
        private final boolean canAfford;

        TributeButton(int x, int y, int width, int cost, boolean canAfford, OnPress onPress) {
            super(x, y, width, HEIGHT, Component.empty(), onPress, DEFAULT_NARRATION);
            this.cost = String.valueOf(cost);
            this.canAfford = canAfford;
        }

        static int widthFor(Font font, int cost) {
            return PADDING + font.width(String.valueOf(cost)) + 2 + ICON_SIZE + PADDING / 2;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            Font font = Minecraft.getInstance().font;
            int textY = getY() + (height - font.lineHeight) / 2 + 1;
            int x = getX() + PADDING;
            graphics.drawString(font, cost, x, textY, canAfford ? CAN_AFFORD_COLOUR : CANNOT_AFFORD_COLOUR);
            x += font.width(cost) + 2;
            graphics.renderItem(ICON, x, getY() + (height - ICON_SIZE) / 2);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.translatable("gui.narrate.button", Component.empty().append(LABEL).append(" " + cost));
        }
    }
}
