package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.engine.EngineMenu;
import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * The Engine Block screen: a coal slot with a flame over it while there is any, a gauge, the count,
 * and how many more worlds that coal carries the carriage through.
 */
public class EngineScreen extends AbstractContainerScreen<EngineMenu> {

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/container/engine_block.png");

    /** Flame sprite on the texture, drawn over the slot while lit. */
    private static final int FLAME_U = 176, FLAME_V = 0, FLAME_SIZE = 14;
    private static final int FLAME_X = 14, FLAME_Y = 22;
    /** Gauge trough on the panel and its fill sprite. */
    private static final int GAUGE_X = 37, GAUGE_Y = 23, GAUGE_W = 130, GAUGE_H = 5;
    private static final int GAUGE_FILL_U = 0, GAUGE_FILL_V = 170;
    private static final int TEXT_X = 36, COUNT_Y = 33, STATUS_Y = 45, TEXT_WIDTH = 132;
    private static final int TEXT_COLOUR = 0x404040;

    public EngineScreen(EngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        int coal = menu.coal();
        if (coal > 0) {
            graphics.blit(TEXTURE, x + FLAME_X, y + FLAME_Y, FLAME_U, FLAME_V, FLAME_SIZE, FLAME_SIZE);
            int fill = Math.max(1, Math.round(GAUGE_W * (coal / (float) DriftEngine.MAX_COAL)));
            graphics.blit(TEXTURE, x + GAUGE_X, y + GAUGE_Y, GAUGE_FILL_U, GAUGE_FILL_V, fill, GAUGE_H);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        int coal = menu.coal();
        graphics.drawString(font, Component.translatable("gui.dungeontrain.engine_block.coal", coal,
                DriftEngine.MAX_COAL), TEXT_X, COUNT_Y, TEXT_COLOUR, false);
        List<FormattedCharSequence> lines = font.split(status(coal), TEXT_WIDTH);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), TEXT_X, STATUS_Y + i * (font.lineHeight + 1), TEXT_COLOUR, false);
        }
    }

    private static Component status(int coal) {
        if (coal <= 0) return Component.translatable("gui.dungeontrain.engine_block.empty");
        String locale = Minecraft.getInstance().getLanguageManager().getSelected();
        return PluralRules.clause(locale, "gui.dungeontrain.engine_block.worlds", coal);
    }
}
