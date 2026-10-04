package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisposableCamera;
import games.brennan.dungeontrain.compat.photo.OwnPhotoTribute;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import games.brennan.dungeontrain.compat.photo.TributePayment;
import games.brennan.dungeontrain.compat.photo.ViewOrdinal;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OwnPhotoTributePacket;
import games.brennan.dungeontrain.net.PhotoTributePacket;
import io.github.mortuusars.exposure.client.gui.screen.PhotographScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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
import org.lwjgl.glfw.GLFW;

/**
 * Adds two buttons under a found player photo while it is open in Exposure's viewer:
 * how many views it has left, the Tribute cost beside an emerald — pay it to keep the photo
 * travelling — and {@code X} to close. Either way the photo burns afterwards. A player's own fresh
 * print, which burns after viewing too, gets the {@code X} and — when offered — a Tribute that
 * posts it to the passenger log. Space closes either. Each button is only as wide as what is drawn on it.
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
    private static ViewCounter viewsShown;

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
        if (player == null) return;
        ItemStack photo = heldFoundPhoto(player);
        Font font = minecraft.font;
        Component closeLabel = Component.translatable("gui.dungeontrain.photo_tribute.close");
        int closeWidth = font.width(CLOSE_MARK) + 2 * PADDING;
        int y = screen.height - HEIGHT - BOTTOM_MARGIN;
        if (photo.isEmpty()) {
            if (!DisposableCamera.holdsBurnAfterViewing(player.getMainHandItem())
                && !DisposableCamera.holdsBurnAfterViewing(player.getOffhandItem())) return;
            addOwnPrintButtons(event, screen, player, font, closeLabel, closeWidth, y);
            return;
        }

        int cost = SharedPhotos.tributeCost(photo);
        int emeralds = TributePayment.worth(player.getInventory());
        boolean canAfford = TributePayment.canPay(player.getInventory(), cost);
        int viewsLeft = SharedPhotos.viewsLeft(photo);
        int seen = SharedPhotos.viewsSeen(photo);
        // This viewer's own place: 1 / 10 for the first to open it, 10 / 10 for the last.
        ViewCounter views = new ViewCounter(font, seen + 1, seen + viewsLeft);
        int viewsWidth = views.getWidth();
        int tributeWidth = TributeButton.widthFor(font, cost);
        int left = (screen.width - viewsWidth - GAP - tributeWidth - GAP - closeWidth) / 2;

        views.setPosition(left, y);
        views.setTooltip(Tooltip.create(viewerLine(seen + 1).append("\n").append(remainingLine(viewsLeft - 1))));
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
        shown = List.of(tribute, close);
        viewsShown = views;
    }

    /**
     * A player's own fresh print: {@code X}, and — when the server offers it — a Tribute that posts the
     * photo to the passenger log, its cost tripling with each one this life.
     */
    private static void addOwnPrintButtons(ScreenEvent.Init.Post event, Screen screen, LocalPlayer player, Font font,
                                           Component closeLabel, int closeWidth, int y) {
        String name = player.getGameProfile().getName();
        boolean own = OwnPhotoTribute.isOwnPrint(player.getMainHandItem(), name)
            || OwnPhotoTribute.isOwnPrint(player.getOffhandItem(), name);
        int cost = own ? OwnPhotoTributeClientState.cost() : 0;
        int tributeWidth = cost > 0 ? TributeButton.widthFor(font, cost) + GAP : 0;
        int left = (screen.width - tributeWidth - closeWidth) / 2;
        List<Button> buttons = new java.util.ArrayList<>();
        if (cost > 0) {
            boolean canAfford = TributePayment.canPay(player.getInventory(), cost);
            TributeButton tribute = new TributeButton(left, y, tributeWidth - GAP, cost, canAfford, button -> {
                DungeonTrainNet.sendToServer(new OwnPhotoTributePacket());
                screen.onClose();
            });
            tribute.active = canAfford;
            tribute.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.own_photo_tribute.offer")
                .append("\n").append(Component.translatable("gui.dungeontrain.own_photo_tribute.boost",
                    SharedPhotos.VIEWS_MAX, SharedPhotos.VIEWS_MAX * SharedPhotos.OWN_BOOST_FACTOR))
                .append("\n").append(Component.translatable("gui.dungeontrain.photo_tribute.have",
                    TributePayment.worth(player.getInventory())))));
            event.addListener(tribute);
            buttons.add(tribute);
        }
        Button close = Button.builder(Component.literal(CLOSE_MARK), button -> screen.onClose())
            .bounds(left + tributeWidth, y, closeWidth, HEIGHT)
            .createNarration(message -> Component.translatable("gui.narrate.button", closeLabel)).build();
        event.addListener(close);
        buttons.add(close);
        shownOn = screen;
        shown = List.copyOf(buttons);
    }

    /** Exposure's viewer draws only the photo, never its widgets, so the buttons are drawn here. */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() != shownOn) return;
        if (viewsShown != null) viewsShown.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        for (Button button : shown) {
            button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    /** Space closes the photo, like the X. */
    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() != shownOn || event.getKeyCode() != GLFW.GLFW_KEY_SPACE) return;
        event.getScreen().onClose();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() != shownOn) return;
        shownOn = null;
        shown = List.of();
        viewsShown = null;
    }

    /** "You're the First." / "You're the 4th." — where this viewer comes in the photo's life. */
    private static MutableComponent viewerLine(int place) {
        return place == 1
            ? Component.translatable("gui.dungeontrain.photo_tribute.viewer.first")
            : Component.translatable("gui.dungeontrain.photo_tribute.viewer." + ViewOrdinal.ending(place), place);
    }

    /** How many people can still see the photo after this viewer. */
    private static MutableComponent remainingLine(int after) {
        if (after <= 0) return Component.translatable("gui.dungeontrain.photo_tribute.views_left.none");
        return after == 1
            ? Component.translatable("gui.dungeontrain.photo_tribute.views_left.one")
            : Component.translatable("gui.dungeontrain.photo_tribute.views_left", after);
    }

    /**
     * {@code place / total} as plain text: which viewer of the photo this player is, over how many
     * will ever get to see it. The count is full size; {@code / total} is smaller, at its bottom right.
     */
    private static final class ViewCounter extends AbstractWidget {

        private static final float SMALL = 0.6f;
        private static final int TEXT_COLOUR = 0xFFFFFF;
        private static final int SMALL_COLOUR = 0xC8C8C8;

        private final Font font;
        private final String seen;
        private final String total;

        ViewCounter(Font font, int seen, int total) {
            super(0, 0, widthFor(font, seen, total), HEIGHT, Component.literal(seen + " / " + total));
            this.font = font;
            this.seen = String.valueOf(seen);
            this.total = "/ " + total;
        }

        private static int widthFor(Font font, int seen, int total) {
            return PADDING + font.width(String.valueOf(seen)) + 2 + Math.round(font.width("/ " + total) * SMALL) + PADDING;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int textY = getY() + (height - font.lineHeight) / 2 + 1;
            int x = getX() + PADDING;
            graphics.drawString(font, seen, x, textY, TEXT_COLOUR);
            float smallX = x + font.width(seen) + 2;
            float smallY = textY + font.lineHeight - font.lineHeight * SMALL;
            graphics.pose().pushPose();
            graphics.pose().translate(smallX, smallY, 0);
            graphics.pose().scale(SMALL, SMALL, 1);
            graphics.drawString(font, total, 0, 0, SMALL_COLOUR);
            graphics.pose().popPose();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false; // text, not a control
        }
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
