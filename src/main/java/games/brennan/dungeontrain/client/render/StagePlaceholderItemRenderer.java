package games.brennan.dungeontrain.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * Item renderer for the stage placeholder blocks: in GUI contexts (inventory, creative tab,
 * hotbar) the icon is the block the placeholder <b>resolves to for the currently selected stage</b>
 * with the placeholder's own lettered tile ghosted over it at {@link #OVERLAY_ALPHA}. The selected
 * stage is the editor's effective stage, synced per player by
 * {@link games.brennan.dungeontrain.net.StageIconPalettePacket} into
 * {@link games.brennan.dungeontrain.client.menu.ClientStagePalette} — outside the editor there is
 * none, so the icon is the plain tile, as it is in every non-GUI context (hand, item frame, ground).
 *
 * <p>The item models are {@code builtin/entity} so this runs; the placeholder's own look comes from
 * its <em>block</em> model (default state — the straight stair, the bottom slab, the closed
 * trapdoor) with that model's display transforms, except the door, which uses the flat
 * {@link #DOOR_SPRITE} sprite like vanilla doors do.</p>
 */
public final class StagePlaceholderItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** Opacity of the placeholder tile drawn over the resolved block. */
    public static final float OVERLAY_ALPHA = 0.30f;

    /** The door's flat item sprite, registered via {@code ModelEvent.RegisterAdditional}. */
    public static final ModelResourceLocation DOOR_SPRITE = ModelResourceLocation.standalone(
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "item/stage_door_sprite"));

    private static final RandomSource RANDOM = RandomSource.create(42L);

    public StagePlaceholderItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        Block placeholder = blockItem.getBlock();
        String name = BuiltInRegistries.BLOCK.getKey(placeholder).getPath();
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer itemRenderer = mc.getItemRenderer();

        // ItemRenderer applied the builtin model's (identity) transform and a -0.5 translate before
        // handing over; undo the translate so each layer can apply its own model's transforms.
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);

        boolean drewBase = false;
        if (context == ItemDisplayContext.GUI) {
            Block resolved = resolvedFor(name);
            if (resolved != null && resolved != placeholder) {
                ItemStack resolvedStack = new ItemStack(resolved);
                BakedModel base = itemRenderer.getModel(resolvedStack, null, null, 0);
                pose.pushPose();
                base = base.applyTransform(context, pose, false);
                pose.translate(-0.5f, -0.5f, -0.5f);
                itemRenderer.renderModelLists(base, resolvedStack, light, overlay, pose,
                    buffers.getBuffer(ItemBlockRenderTypes.getRenderType(resolvedStack, true)));
                pose.popPose();
                drewBase = true;
            }
        }

        BakedModel tile = placeholder instanceof DoorBlock
            ? mc.getModelManager().getModel(DOOR_SPRITE)
            : mc.getBlockRenderer().getBlockModel(placeholder.defaultBlockState());
        pose.pushPose();
        tile = tile.applyTransform(context, pose, false);
        pose.translate(-0.5f, -0.5f, -0.5f);
        float alpha = drewBase ? OVERLAY_ALPHA : 1.0f;
        VertexConsumer vc = buffers.getBuffer(drewBase ? Sheets.translucentItemSheet() : Sheets.cutoutBlockSheet());
        renderQuads(tile, pose, vc, light, overlay, alpha);
        pose.popPose();

        pose.popPose();
    }

    /** The block {@code placeholderName} resolves to for the selected stage, or null when none is selected. */
    private static Block resolvedFor(String placeholderName) {
        String id = games.brennan.dungeontrain.client.menu.ClientStagePalette.resolved(placeholderName);
        if (id == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        Block b = BuiltInRegistries.BLOCK.get(rl);
        return StagePlaceholderBlocks.isPlaceholder(b) ? null : b;
    }

    /** Every quad of {@code model} (all faces + unculled) at {@code alpha}, untinted. */
    private static void renderQuads(BakedModel model, PoseStack pose, VertexConsumer vc,
                                    int light, int overlay, float alpha) {
        PoseStack.Pose last = pose.last();
        for (Direction dir : Direction.values()) {
            RANDOM.setSeed(42L);
            put(model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null), last, vc, light, overlay, alpha);
        }
        RANDOM.setSeed(42L);
        put(model.getQuads(null, null, RANDOM, ModelData.EMPTY, null), last, vc, light, overlay, alpha);
    }

    private static void put(List<BakedQuad> quads, PoseStack.Pose pose, VertexConsumer vc,
                            int light, int overlay, float alpha) {
        for (BakedQuad q : quads) {
            vc.putBulkData(pose, q, 1.0f, 1.0f, 1.0f, alpha, light, overlay);
        }
    }
}
