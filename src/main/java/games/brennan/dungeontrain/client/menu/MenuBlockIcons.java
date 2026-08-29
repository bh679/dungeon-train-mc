package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;

/**
 * Shared inventory-style block icon for world-space menu panels, keyed on a bare Block registry id
 * ({@code "minecraft:stone_bricks"}). Extracted from
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRenderer}'s
 * {@code drawBlockIcon} so the Stages-panel row strips and the Stage Blocks panel render icons the
 * same way: {@link ItemDisplayContext#GUI} + {@link LightTexture#FULL_BRIGHT}, {@code z 0.002}
 * lift over the backdrop quad, BARRIER fallback for blocks without an item form.
 */
public final class MenuBlockIcons {

    private MenuBlockIcons() {}

    /**
     * Draw the icon for {@code blockId} centred vertically on {@code rowCY}, with the icon's centre
     * at {@code centerX}. {@code iconSize} is the panel-local icon edge length. Unknown / air /
     * itemless ids fall back to a BARRIER icon so the slot never reads as empty.
     */
    public static void drawBlockIcon(PoseStack ps, MultiBufferSource buffer,
                                     String blockId, double centerX, double rowCY, double iconSize) {
        draw(ps, buffer, iconStackFor(blockId), centerX, rowCY, iconSize, null);
    }

    /**
     * Same as {@link #drawBlockIcon} but keyed on a bare Item registry id
     * ({@code "minecraft:diamond_chestplate"}). Used by the container-contents panel, whose rows
     * are item ids rather than block ids — an item like {@code bread} has no Block form, so
     * {@link #iconStackFor} would fall back to BARRIER for it.
     */
    public static void drawItemIcon(PoseStack ps, MultiBufferSource buffer,
                                    String itemId, double centerX, double rowCY, double iconSize) {
        draw(ps, buffer, itemIconStackFor(itemId), centerX, rowCY, iconSize, null);
    }

    /**
     * As {@link #drawItemIcon}, but routed into {@code icons} when non-null — the screen-space
     * host replays that batch through vanilla's GUI item path, which is the only way these
     * render at all outside the level pass. See {@link PanelIconBatch}.
     */
    public static void drawItemIcon(PoseStack ps, MultiBufferSource buffer, String itemId,
                                    double centerX, double rowCY, double iconSize,
                                    @Nullable PanelIconBatch icons) {
        draw(ps, buffer, itemIconStackFor(itemId), centerX, rowCY, iconSize, icons);
    }

    /** {@link #drawItemIcon(PoseStack, MultiBufferSource, String, double, double, double, PanelIconBatch)} for a block id. */
    public static void drawBlockIcon(PoseStack ps, MultiBufferSource buffer, String blockId,
                                     double centerX, double rowCY, double iconSize,
                                     @Nullable PanelIconBatch icons) {
        draw(ps, buffer, iconStackFor(blockId), centerX, rowCY, iconSize, icons);
    }

    private static void draw(PoseStack ps, MultiBufferSource buffer, ItemStack stack,
                             double centerX, double rowCY, double iconSize,
                             @Nullable PanelIconBatch icons) {
        if (icons != null) {
            icons.add(ps, stack, centerX, rowCY, iconSize);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        ps.pushPose();
        ps.translate(centerX, rowCY, 0.002);
        ps.scale((float) iconSize, (float) iconSize, (float) iconSize);
        itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.GUI,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            ps,
            buffer,
            mc.level,
            0
        );
        ps.popPose();
    }

    /** Resolve the icon ItemStack for a bare item id; BARRIER fallback for unknown / air ids. */
    public static ItemStack itemIconStackFor(String itemId) {
        ResourceLocation loc = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        Item item = loc == null ? null : BuiltInRegistries.ITEM.getOptional(loc).orElse(null);
        return (item == null || item == Items.AIR) ? new ItemStack(Items.BARRIER) : new ItemStack(item);
    }

    /** Resolve the icon ItemStack for a bare block id; BARRIER fallback. */
    public static ItemStack iconStackFor(String blockId) {
        ResourceLocation loc = ResourceLocation.tryParse(blockId == null ? "" : blockId);
        Block block = loc == null ? null : BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
        if (block == null || block == Blocks.AIR) return new ItemStack(Items.BARRIER);
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            // Liquids have no item form — fall back to the fluid's bucket
            // rather than BARRIER. Mirrors BlockVariantMenuRenderer.drawBlockIcon.
            Item bucket = games.brennan.dungeontrain.editor.VariantLiquids.bucketIcon(
                block.defaultBlockState());
            if (bucket != null) item = bucket;
        }
        return (item == null || item == Items.AIR) ? new ItemStack(Items.BARRIER) : new ItemStack(item);
    }
}
