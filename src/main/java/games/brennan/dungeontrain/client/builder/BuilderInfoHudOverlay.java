package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderInfoLines;
import games.brennan.dungeontrain.client.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.List;

/**
 * What you're building, pinned to the top of the screen while you build it.
 *
 * <p>The pause menu already says this, but you spend the session outside the pause menu — and the
 * one fact that matters most, that the build has never been saved, is invisible until you go
 * looking. Here it's in front of you the whole time.</p>
 *
 * <p>Centred rather than top-left because the dev version read-out already owns that corner, and
 * this belongs to the world you're in rather than to the build of the mod.</p>
 *
 * <p>No size or weight: those are inspection figures, wanted when you're deciding something in the
 * tools panel, not while placing blocks.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderInfoHudOverlay {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_info");

    private static final int TOP_MARGIN = 4;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int DRAFT_COLOUR = 0xFFFFD070;
    private static final int DETAIL_COLOUR = 0xFFA0A0A0;

    private BuilderInfoHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.player == null) {
                return;
            }
            // Empty outside a builder world — the state is only ever populated by the bounds packet,
            // which reports an empty mode for every other world.
            List<String> lines = BuilderInfoPanel.currentLines(BuilderInfoPanel.Content.IDENTITY);
            if (lines.isEmpty()) {
                return;
            }
            // One row: the name, then everything else after it. Two draws rather than one because
            // the name carries the draft colour and the rest doesn't — the whole point of the row is
            // that "unsaved" is legible without reading it.
            String name = lines.get(0);
            String details = String.join(BuilderInfoLines.JOIN, lines.subList(1, lines.size()));
            String tail = details.isEmpty() ? "" : BuilderInfoLines.JOIN + details;

            int nameWidth = HudText.scaledWidth(mc.font, name);
            int totalWidth = nameWidth + HudText.scaledWidth(mc.font, tail);
            int x = (graphics.guiWidth() - totalWidth) / 2;

            HudText.drawScaled(graphics, mc.font, name, x, TOP_MARGIN,
                    BuilderInfoPanel.isDraft() ? DRAFT_COLOUR : NAME_COLOUR, true);
            if (!tail.isEmpty()) {
                HudText.drawScaled(graphics, mc.font, tail, x + nameWidth, TOP_MARGIN,
                        DETAIL_COLOUR, true);
            }
        });
    }
}
