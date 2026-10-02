package games.brennan.dungeontrain.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
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
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * trapdoor) with that model's display transforms, except the ones in {@link #ICON_MODELS}, whose
 * block model makes no icon: fences, walls and buttons use their {@code _inventory} model, the
 * door and the glass panes a flat sprite, like their vanilla counterparts.</p>
 */
public final class StagePlaceholderItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** Opacity of the placeholder tile drawn over the resolved block. */
    public static final float OVERLAY_ALPHA = 0.30f;
    /** Whole-icon opacity for a slot that only repeats an earlier one (looped list read). */
    public static final float REPEAT_ALPHA = 0.50f;

    /**
     * The placeholders whose tile is not their block model, by name — see
     * {@link StagePlaceholderIcons}. Registered via {@code ModelEvent.RegisterAdditional}.
     */
    public static final Map<String, ModelResourceLocation> ICON_MODELS = iconModels();

    private static Map<String, ModelResourceLocation> iconModels() {
        Map<String, ModelResourceLocation> out = new HashMap<>();
        for (String name : StagePlaceholderBlocks.names()) {
            String path = StagePlaceholderIcons.iconModel(name);
            if (path != null) {
                out.put(name, ModelResourceLocation.standalone(
                    ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path)));
            }
        }
        return Map.copyOf(out);
    }

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
        // A screen naming the answer draws the cell whole: the repeat dimming reads the selected
        // stage's palette, which is not the one being shown.
        boolean repeat = context == ItemDisplayContext.GUI && FORCED.get() == null
            && games.brennan.dungeontrain.client.menu.ClientStagePalette.isRepeat(name);
        float whole = repeat ? REPEAT_ALPHA : 1.0f;
        if (context == ItemDisplayContext.GUI) {
            Block resolved = resolvedFor(name);
            if (resolved != null && resolved != placeholder) {
                ItemStack resolvedStack = new ItemStack(resolved);
                BakedModel base = itemRenderer.getModel(resolvedStack, null, null, 0);
                pose.pushPose();
                base = base.applyTransform(context, pose, false);
                pose.translate(-0.5f, -0.5f, -0.5f);
                // Both layers go through the same (non-fixed) translucent sheet so they flush in
                // draw order — a fixed cutout buffer would flush AFTER the ghost and cover it.
                renderQuads(base, pose, buffers.getBuffer(Sheets.translucentItemSheet()), light, overlay, whole,
                    resolvedStack);
                pose.popPose();
                drewBase = true;
            }
        }

        ModelResourceLocation icon = ICON_MODELS.get(name);
        BakedModel tile = icon != null
            ? mc.getModelManager().getModel(icon)
            : mc.getBlockRenderer().getBlockModel(placeholder.defaultBlockState());
        pose.pushPose();
        tile = tile.applyTransform(context, pose, false);
        pose.translate(-0.5f, -0.5f, -0.5f);
        float alpha = (drewBase ? OVERLAY_ALPHA : 1.0f) * whole;
        VertexConsumer vc = buffers.getBuffer(Sheets.translucentItemSheet());
        renderQuads(tile, pose, vc, light, overlay, alpha, ItemStack.EMPTY);
        pose.popPose();

        pose.popPose();
    }

    /**
     * A resolution the caller names, for a screen drawing a stage that is not the selected one:
     * while set, every placeholder icon resolves to it instead of the selected stage's answer.
     */
    private static final ThreadLocal<String> FORCED = new ThreadLocal<>();

    /** Draw within {@code body} with every placeholder resolving to {@code blockId} (null = as now). */
    public static void withResolved(String blockId, Runnable body) {
        String before = FORCED.get();
        FORCED.set(blockId);
        try {
            body.run();
        } finally {
            FORCED.set(before);
        }
    }

    /** The block {@code placeholderName} resolves to for the selected stage, or null when none is selected. */
    private static Block resolvedFor(String placeholderName) {
        String forced = FORCED.get();
        String id = forced != null ? forced
            : games.brennan.dungeontrain.client.menu.ClientStagePalette.resolved(placeholderName);
        if (id == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        Block b = BuiltInRegistries.BLOCK.get(rl);
        return StagePlaceholderBlocks.isPlaceholder(b) ? null : b;
    }

    /**
     * Every quad of {@code model} (all faces + unculled) at {@code alpha}; tinted faces take
     * {@code tintSource}'s item colour (the resolved leaves' foliage green), untinted when empty.
     */
    private static void renderQuads(BakedModel model, PoseStack pose, VertexConsumer vc,
                                    int light, int overlay, float alpha, ItemStack tintSource) {
        PoseStack.Pose last = pose.last();
        for (Direction dir : Direction.values()) {
            RANDOM.setSeed(42L);
            put(model.getQuads(null, dir, RANDOM, ModelData.EMPTY, null), last, vc, light, overlay, alpha, tintSource);
        }
        RANDOM.setSeed(42L);
        put(model.getQuads(null, null, RANDOM, ModelData.EMPTY, null), last, vc, light, overlay, alpha, tintSource);
    }

    private static void put(List<BakedQuad> quads, PoseStack.Pose pose, VertexConsumer vc,
                            int light, int overlay, float alpha, ItemStack tintSource) {
        for (BakedQuad q : quads) {
            int rgb = q.isTinted() && !tintSource.isEmpty()
                ? Minecraft.getInstance().getItemColors().getColor(tintSource, q.getTintIndex()) : -1;
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;
            vc.putBulkData(pose, q, r, g, b, alpha, light, overlay);
        }
    }
}
