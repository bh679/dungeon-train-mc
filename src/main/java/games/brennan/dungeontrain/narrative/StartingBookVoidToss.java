package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * "Void Where Prohibited": a player throws their starting book off the train and it goes down into
 * the void.
 *
 * <p>A dropped starting book burns away about three seconds after it leaves the hand
 * ({@code StartingBookEvents}), usually long before it could fall out of the world. So the toss
 * counts when the book is gone, burnt or fallen out, while it was still falling with nothing under
 * it but void: off the ground, well below where it was thrown, over an empty world column. Carriage
 * blocks live in Sable's shipyard, not in the world column under the train, so the fall distance is
 * what keeps a book burning on a carriage's deck over the End's void from counting.</p>
 *
 * <p>The thrower is written onto the item entity when it is thrown: by {@link ItemTossEvent} for a
 * book dropped by hand, and by {@link #markThrower} for the read-and-close throw, which spawns its
 * drop without a thrower.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StartingBookVoidToss {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Advancement action id ({@code dungeon_train/void_starting_book}). */
    public static final String ACTION_ID = "void_starting_book";

    static final String NBT_THROWER = "dt_book_tossed_by";
    static final String NBT_TOSS_Y = "dt_book_tossed_y";

    /** How far below the throw a book must be when it goes, so one burning on the deck never counts. */
    static final double MIN_FALL_BLOCKS = 8.0;

    private StartingBookVoidToss() {}

    /**
     * The rule. Pure.
     *
     * @param fallen      blocks the book is below the height it was thrown from
     * @param columnEmpty nothing in the world column under the book, down to the bottom of the world
     * @param belowWorld  the book is below the world's lowest block (it fell out)
     */
    static boolean countsAsVoidToss(boolean onGround, double fallen, boolean columnEmpty, boolean belowWorld) {
        if (belowWorld) return true;
        return !onGround && fallen >= MIN_FALL_BLOCKS && columnEmpty;
    }

    /** Record {@code player} as the thrower of {@code item}, when it holds a starting book. */
    public static void markThrower(ItemEntity item, Player player) {
        if (item == null || player == null || item.level().isClientSide()) return;
        if (!StartingBookTag.isStartingBook(item.getItem())) return;
        CompoundTag data = item.getPersistentData();
        data.putUUID(NBT_THROWER, player.getUUID());
        data.putDouble(NBT_TOSS_Y, player.getY());
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        markThrower(event.getEntity(), event.getPlayer());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompoundTag data = item.getPersistentData();
        if (!data.hasUUID(NBT_THROWER)) return;
        Entity.RemovalReason reason = item.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return; // chunk unload, dimension change: not gone
        try {
            if (!StartingBookTag.isStartingBook(item.getItem())) return;
            double fallen = data.getDouble(NBT_TOSS_Y) - item.getY();
            boolean belowWorld = item.getY() < level.getMinBuildHeight();
            boolean columnEmpty = belowWorld || level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(item.getX()), Mth.floor(item.getZ())) <= level.getMinBuildHeight();
            if (!countsAsVoidToss(item.onGround(), fallen, columnEmpty, belowWorld)) return;
            credit(level.getServer(), data.getUUID(NBT_THROWER));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Starting book void toss check failed: {}", t.toString());
        }
    }

    private static void credit(MinecraftServer server, UUID thrower) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(thrower);
        if (player == null) return; // thrower logged out mid-fall
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, ACTION_ID);
        LOGGER.info("[DungeonTrain] {} tossed a starting book into the void", player.getGameProfile().getName());
    }
}
