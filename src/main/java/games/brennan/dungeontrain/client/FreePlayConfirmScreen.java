package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.FreePlayConfirmResponsePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Enter Free Play?" — a soft, non-judgemental confirmation shown before a
 * tainting action (a creative/spectator switch or a cheat command) commits.
 * Continue starts Free Play and the held action is re-run server-side; Cancel
 * (or ESC) backs out and the run is untouched. A "Don't show this again"
 * checkbox persists to {@link ClientDisplayConfig} on Continue.
 */
public final class FreePlayConfirmScreen extends Screen {

    private static final int FREE_PLAY_TEAL = 0xFF5BC8C2;

    private final String triggerLabel;
    private Checkbox dontShowBox;
    private boolean responded = false;

    public FreePlayConfirmScreen(String triggerLabel) {
        super(Component.translatable("gui.dungeontrain.free_play.confirm.title"));
        this.triggerLabel = triggerLabel;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        dontShowBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.dont_show"), this.font)
            .pos(cx - 100, cy + 22)
            .selected(ClientDisplayConfig.isFreePlayConfirmOptedOut())
            .build();
        addRenderableWidget(dontShowBox);

        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.continue"),
                b -> respond(true))
            .bounds(cx - 154, cy + 52, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.cancel"),
                b -> respond(false))
            .bounds(cx + 4, cy + 52, 150, 20).build());
    }

    private void respond(boolean confirmed) {
        if (responded) return;
        responded = true;
        if (confirmed && dontShowBox != null && dontShowBox.selected()) {
            ClientDisplayConfig.setFreePlayConfirmOptedOut(true);
        }
        DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(confirmed));
        onClose();
    }

    @Override
    public void onClose() {
        // ESC / external close counts as backing out — but only answer once.
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(false));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.drawCenteredString(this.font, this.title, cx, cy - 70, FREE_PLAY_TEAL);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.free_play.confirm.body"), cx, cy - 48, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("effect.dungeontrain.free_play.desc.1"), cx, cy - 30, 0xFFB8B8B8);
        g.drawCenteredString(this.font,
            Component.translatable("effect.dungeontrain.free_play.desc.2"), cx, cy - 18, 0xFFB8B8B8);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.free_play.confirm.trigger", triggerLabel)
                .withStyle(ChatFormatting.DARK_GRAY), cx, cy + 2, 0xFF8B8B8B);
    }
}
