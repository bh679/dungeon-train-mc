package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;

/**
 * Drops the rolled item stored at {@link ContainerContentsRoller#NBT_POT_LOOT}
 * when a vase ({@code minecraft:decorated_pot}) is broken. Vanilla 1.20.1
 * decorated pots have no inventory slot, so the roller writes the rolled
 * {@link ItemStack} into a custom BE NBT field; this handler materialises
 * that field as a vanilla container-style spill alongside the sherd shards.
 *
 * <p>Runs on {@link EventPriority#HIGH} (before vanilla destroys the BE) and
 * does NOT cancel the event — vanilla sherd drops continue unchanged.
 *
 * <p>Forward-compat: if a future Minecraft version gives decorated pots a
 * native {@link net.minecraft.world.Container}, the roller stops writing
 * {@code dt_pot_loot} and this handler becomes a no-op (it only fires when
 * the field is present).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VasePotLootDrop {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VasePotLootDrop() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        if (!ContainerContentsRoller.isDecoratedPot(level.getBlockState(pos))) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        CompoundTag beTag = be.saveWithoutMetadata(level.registryAccess());
        if (!beTag.contains(ContainerContentsRoller.NBT_POT_LOOT, Tag.TAG_COMPOUND)) return;

        ItemStack stack = ItemStack.parseOptional(level.registryAccess(),
            beTag.getCompound(ContainerContentsRoller.NBT_POT_LOOT));
        if (stack.isEmpty()) return;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] Vase loot drop: {} at {}", stack, pos);
        }

        Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
    }
}
