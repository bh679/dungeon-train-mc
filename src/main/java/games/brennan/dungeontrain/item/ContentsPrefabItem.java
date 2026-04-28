package games.brennan.dungeontrain.item;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Creative-tab prefab item carrying a {@link CarriageContents} id in NBT.
 * Click in the prefab tab puts an instance with the chosen id in the cursor;
 * right-click in world resolves the id via {@link CarriageContentsRegistry} +
 * {@link CarriageContentsStore} and pastes the whole interior contents
 * template at the clicked position. OP-only; consumes the stack on success.
 *
 * <p>Counterpart to {@link VariantPrefabItem} but for the carriage interior
 * contents (containers + their loot).</p>
 */
public final class ContentsPrefabItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ItemStack NBT key carrying the contents id (e.g. "default"). */
    public static final String NBT_PREFAB_ID = "dt_prefab_id";

    public ContentsPrefabItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!player.hasPermissions(2)) {
            sendActionBar(player, "Loot prefab requires OP", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        ItemStack stack = ctx.getItemInHand();
        String prefabId = decodePrefabId(stack.getTag());
        if (prefabId == null) {
            sendActionBar(player, "Prefab item missing id tag", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        Optional<CarriageContents> contentsOpt = CarriageContentsRegistry.find(prefabId);
        if (contentsOpt.isEmpty()) {
            sendActionBar(player, "Unknown contents '" + prefabId + "'", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        CarriageContents contents = contentsOpt.get();

        CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
        Vec3i interiorSize = CarriageContentsTemplate.interiorSize(dims);
        Optional<StructureTemplate> templateOpt =
            CarriageContentsStore.get(serverLevel, contents, interiorSize);
        if (templateOpt.isEmpty()) {
            sendActionBar(player, "No saved interior for '" + prefabId + "'", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        StructureTemplate template = templateOpt.get();

        BlockPos placePos = ctx.getClickedPos().relative(ctx.getClickedFace());
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(Rotation.NONE)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(false);

        boolean placed = template.placeInWorld(
            serverLevel, placePos, placePos, settings, serverLevel.random, 2);
        if (!placed) {
            sendActionBar(player, "Placement failed for '" + prefabId + "'", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        sendActionBar(player, "Placed contents '" + prefabId + "' at "
            + placePos.getX() + "," + placePos.getY() + "," + placePos.getZ(),
            ChatFormatting.GREEN);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /** Build a stack of this item carrying the given prefab id. */
    public static ItemStack stackForPrefab(Item item, String prefabId) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_PREFAB_ID, prefabId);
        stack.setTag(tag);
        return stack;
    }

    /** Read the prefab id from an item's NBT, or null if absent/blank. */
    @Nullable
    public static String decodePrefabId(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_PREFAB_ID, Tag.TAG_STRING)) return null;
        String id = tag.getString(NBT_PREFAB_ID);
        return id.isEmpty() ? null : id;
    }

    private static void sendActionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(
            Component.literal(text).withStyle(colour),
            true);
    }

    @Override
    public Component getName(ItemStack stack) {
        String id = decodePrefabId(stack.getTag());
        if (id == null) return super.getName(stack);
        return Component.literal(super.getName(stack).getString() + ": " + id);
    }
}
