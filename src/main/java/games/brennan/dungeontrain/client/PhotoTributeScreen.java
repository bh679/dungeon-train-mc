package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotoTributePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * Shown after a found player photo is closed: pay Tribute (one diamond, which keeps the photo
 * travelling to other players) or just close. Closing costs nothing.
 */
public final class PhotoTributeScreen extends Screen {

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 8;
    private static final int TEXT_COLOUR = 0xFFFFFF;
    private static final int PROMPT_COLOUR = 0xAAAAAA;

    public PhotoTributeScreen() {
        super(Component.translatable("gui.dungeontrain.photo_tribute.title"));
    }

    private boolean canPay() {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        return player != null && (player.getAbilities().instabuild || player.getInventory().countItem(Items.DIAMOND) > 0);
    }

    @Override
    protected void init() {
        int y = height / 2 + GAP;
        Button tribute = Button.builder(Component.translatable("gui.dungeontrain.photo_tribute.tribute"), button -> {
            DungeonTrainNet.sendToServer(new PhotoTributePacket());
            onClose();
        }).bounds(width / 2 - BUTTON_WIDTH - GAP / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        if (!canPay()) {
            tribute.active = false;
            tribute.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.photo_tribute.no_diamond")));
        }
        addRenderableWidget(tribute);
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.photo_tribute.close"), button -> onClose())
            .bounds(width / 2 + GAP / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 3 * GAP - font.lineHeight, TEXT_COLOUR);
        graphics.drawCenteredString(font, Component.translatable("gui.dungeontrain.photo_tribute.prompt"),
            width / 2, height / 2 - GAP - font.lineHeight / 2, PROMPT_COLOUR);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
