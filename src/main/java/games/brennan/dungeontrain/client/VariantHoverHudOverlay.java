package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side HUD overlay: when the server tells us the crosshair is on a
 * variant-flagged block (via {@link games.brennan.dungeontrain.net.VariantHoverPacket}),
 * render a small row of item icons just above the hotbar showing every
 * candidate block the position can roll into.
 *
 * <p>No font text, no coordinates — just the icons. The outline particles
 * already tell you *where* the variant is; the icons tell you *what* it
 * varies between.</p>
 *
 * <p>The hover list is cached in a plain static field. Server updates fire
 * on {@code ctx.enqueueWork()} which lands on the client main thread, same
 * thread the overlay runs on — no synchronisation needed.</p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.MOD,
    value = Dist.CLIENT
)
public final class VariantHoverHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mutated on the main client thread from packet handling. */
    private static List<ItemStack> hoverStacks = Collections.emptyList();

    /** Icon width and spacing in GUI pixels. */
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 2;

    /** Distance above the hotbar in GUI pixels. */
    private static final int OFFSET_ABOVE_HOTBAR = 24;

    private VariantHoverHudOverlay() {}

    /**
     * Called from {@code VariantHoverPacket.handle} on the client main
     * thread. Replaces the hover set atomically.
     */
    public static void setHover(List<ResourceLocation> blockIds) {
        if (blockIds.isEmpty()) {
            hoverStacks = Collections.emptyList();
            return;
        }
        List<ItemStack> out = new ArrayList<>(blockIds.size());
        for (ResourceLocation id : blockIds) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                // Blocks without a matching item (rare) — use a barrier
                // placeholder so the slot still reads as "some block" to
                // the player rather than being dropped silently.
                out.add(new ItemStack(Items.BARRIER));
            } else {
                out.add(new ItemStack(item));
            }
        }
        hoverStacks = List.copyOf(out);
    }

    public static void clear() {
        hoverStacks = Collections.emptyList();
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        IGuiOverlay overlay = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            List<ItemStack> stacks = hoverStacks;
            if (stacks.isEmpty()) return;
            drawIconRow(graphics, stacks, screenWidth, screenHeight);
        };
        event.registerAboveAll("variant_hover", overlay);
        LOGGER.info("Variant hover HUD overlay registered");
    }

    private static void drawIconRow(GuiGraphics graphics, List<ItemStack> stacks, int screenWidth, int screenHeight) {
        int totalWidth = stacks.size() * ICON_SIZE + (stacks.size() - 1) * ICON_GAP;
        int x = (screenWidth - totalWidth) / 2;
        int y = screenHeight - OFFSET_ABOVE_HOTBAR - ICON_SIZE;

        // Dark translucent backdrop behind the row so the icons read cleanly
        // against bright terrain (e.g. glass ceiling + end-rod particles).
        int pad = 2;
        graphics.fill(x - pad, y - pad, x + totalWidth + pad, y + ICON_SIZE + pad, 0x80000000);

        int cursor = x;
        for (ItemStack stack : stacks) {
            graphics.renderItem(stack, cursor, y);
            // No count decoration — the number in the stack isn't meaningful
            // for variants (we always pass a stack of 1).
            cursor += ICON_SIZE + ICON_GAP;
        }
    }
}
