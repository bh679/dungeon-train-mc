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
 * The Drifting Engine Block screen: under the title, the drifting carriage the engine is in, by its name
 * id; then the coal slot and this engine's gauge side by side, and under them how many more worlds this
 * engine's coal lasts, plus, when other engines in the carriage hold coal too, how far all of them carry
 * it. With no carriage the fuel lines give way to a line saying so: fuel only matters to a carriage.
 */
public class EngineScreen extends AbstractContainerScreen<EngineMenu> {

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/container/drifting_engine_block.png");

    /**
     * Gauge fill inside the trough beside the coal slot, centred on the slot's row. Banked coal fills it
     * in ember; coal put in this world carries on from there in a lighter ember, so both read as fuel
     * while showing which part can still be taken back. Both sprites span the full width, so the light
     * part picks up the gradient where the banked part leaves off.
     */
    private static final int GAUGE_X = 37, GAUGE_Y = 25 + EngineMenu.CARRIAGE_LINE_H, GAUGE_W = 130, GAUGE_H = 5;
    private static final int GAUGE_FILL_U = 0, GAUGE_FILL_V = 180, GAUGE_INPUT_V = 186;
    /** The status line, under the slot row at the full width of the panel. */
    private static final int TEXT_X = 12, STATUS_Y = 42 + EngineMenu.CARRIAGE_LINE_H, TEXT_WIDTH = 156;
    private static final int TEXT_COLOUR = 0x404040;
    /** The carriage line, one line under the title, in the lighter grey a label's detail takes. */
    private static final int CARRIAGE_Y_BELOW_TITLE = 10, CARRIAGE_COLOUR = 0x808080;

    public EngineScreen(EngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 166 + EngineMenu.CARRIAGE_LINE_H;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        EmptyCoalIcon.refresh();
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
        if (menu.getSlot(0).getItem().isEmpty()) EmptyCoalIcon.draw(graphics, x + EngineMenu.COAL_X, y + EngineMenu.COAL_Y);
        int banked = gaugeWidth(menu.banked());
        int total = gaugeWidth(menu.coal());
        if (banked > 0) {
            graphics.blit(TEXTURE, x + GAUGE_X, y + GAUGE_Y, GAUGE_FILL_U, GAUGE_FILL_V, banked, GAUGE_H);
        }
        if (total > banked) {
            graphics.blit(TEXTURE, x + GAUGE_X + banked, y + GAUGE_Y, GAUGE_FILL_U + banked, GAUGE_INPUT_V,
                    total - banked, GAUGE_H);
        }
    }

    /**
     * Pixels of gauge for {@code coal}, out of {@link DriftEngine#MAX_COAL}: at least one for any coal at
     * all, so a single lump still shows, and full for an engine fed above the cap before it came down.
     */
    private static int gaugeWidth(int coal) {
        if (coal <= 0) return 0;
        return Math.min(GAUGE_W, Math.max(1, Math.round(GAUGE_W * (coal / (float) DriftEngine.MAX_COAL))));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        graphics.drawString(font, carriageLine(menu.carriageId()), titleLabelX, titleLabelY + CARRIAGE_Y_BELOW_TITLE,
                CARRIAGE_COLOUR, false);
        List<FormattedCharSequence> lines = new java.util.ArrayList<>();
        if (!menu.inCarriage()) {
            // No carriage: nothing for the fuel to carry anywhere.
            lines.addAll(font.split(Component.translatable("gui.dungeontrain.engine_block.no_carriage"), TEXT_WIDTH));
        } else {
            int own = menu.coal();
            int carriage = menu.carriageCoal();
            lines.addAll(font.split(status(own, carriage), TEXT_WIDTH));
            // Other engines in the carriage hold coal too: say how far all of them carry it.
            if (carriage > own) {
                lines.addAll(font.split(Component.translatable("gui.dungeontrain.engine_block.carriage", carriage), TEXT_WIDTH));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), TEXT_X, STATUS_Y + i * (font.lineHeight + 1), TEXT_COLOUR, false);
        }
    }

    /** The line under the title: the carriage's name id (never translated), or that there is none. */
    private static Component carriageLine(String carriageId) {
        return carriageId.isEmpty()
                ? Component.translatable("gui.dungeontrain.engine_block.carriage_not_found")
                : Component.translatable("gui.dungeontrain.engine_block.carriage_id", carriageId);
    }

    /** This engine's line: the worlds its own coal lasts, or spent while others still burn, or the end of the line. */
    private static Component status(int own, int carriage) {
        if (own <= 0) {
            return Component.translatable(carriage > 0 ? "gui.dungeontrain.engine_block.spent" : "gui.dungeontrain.engine_block.empty");
        }
        String locale = Minecraft.getInstance().getLanguageManager().getSelected();
        return PluralRules.clause(locale, "gui.dungeontrain.engine_block.worlds", own);
    }
}
