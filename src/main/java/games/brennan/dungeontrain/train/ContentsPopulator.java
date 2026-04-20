package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.Ship;

/**
 * Places per-carriage interior contents — chests, lecterns, mobs — on
 * demand once a player comes close enough. Runs AFTER the VS ship is
 * assembled so blocks and entities attach to the moving ship rather than
 * the world.
 *
 * <p>Block-type contents (STORAGE, NARRATIVE) are placed via
 * {@code level.setBlock} at the carriage's current ship-to-world position.
 * VS intercepts the set-block and routes the change to the ship chunk.
 *
 * <p>Entity-type contents (ENEMIES) are spawned at the transformed world
 * position and tagged with {@link #TAG_CONTENT} so
 * {@link games.brennan.dungeontrain.event.TrainTickEvents} does not kill
 * them on the next tick.
 */
public final class ContentsPopulator {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String TAG_CONTENT = "dungeontrain.content";
    public static final double PROXIMITY_CARTS = 3.0;
    public static final double PROXIMITY_BLOCKS = PROXIMITY_CARTS * CarriageTemplate.LENGTH;

    private static final String[] BOOK_SNIPPETS = {
        "{\"text\":\"The train has been moving for longer than anyone can remember.\"}",
        "{\"text\":\"Do not open the doors between carriages. The rules were clear.\"}",
        "{\"text\":\"Chapter 7: The Conductor's hand is cold, but the ticket is warm.\"}",
        "{\"text\":\"We counted carriages until sleep. 416. Then we gave up.\"}",
        "{\"text\":\"If you meet yourself in the dining car, do not make eye contact.\"}",
        "{\"text\":\"The rails end somewhere. The train does not.\"}",
        "{\"text\":\"Take what you need from the chests. Leave what you find in the beds.\"}",
        "{\"text\":\"Listen for the whistle. That is when they come.\"}"
    };

    private ContentsPopulator() {}

    /**
     * Populate the interior of one carriage. {@code carriageIndex} is the
     * carriage's position in the train (0-based from the spawn origin).
     * {@code originShipLocal} is the ship-local BlockPos of the first
     * carriage's minimum corner (captured at assembly time).
     */
    public static void populate(
        ServerLevel level,
        Ship ship,
        int carriageIndex,
        BlockPos originShipLocal,
        CarriageSpec spec
    ) {
        switch (spec.contents()) {
            case EMPTY -> {}
            case STORAGE -> placeChest(level, ship, originShipLocal, carriageIndex);
            case NARRATIVE -> placeLectern(level, ship, originShipLocal, carriageIndex);
            case ENEMIES -> spawnEnemies(level, ship, originShipLocal, carriageIndex);
        }
    }

    private static void placeChest(ServerLevel level, Ship ship, BlockPos originShipLocal, int carriageIndex) {
        // yOffset=2: floor block sits at shipyard Y=origin.Y, chest block sits above
        // it so the carriage floor remains intact and the chest is visible from above.
        Vector3d shipLocal = carriageCentreShipLocal(originShipLocal, carriageIndex, 2);
        BlockPos worldPos = toWorldBlockPos(ship, shipLocal);
        level.setBlock(worldPos, Blocks.CHEST.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(worldPos);
        if (be instanceof RandomizableContainerBlockEntity chest) {
            chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, level.random.nextLong());
            chest.setChanged();
            LOGGER.debug("[DungeonTrain] Placed STORAGE chest at {} (carriage {})", worldPos, carriageIndex);
        } else {
            LOGGER.warn("[DungeonTrain] STORAGE: expected chest BE at {}, got {}", worldPos, be);
        }
    }

    private static void placeLectern(ServerLevel level, Ship ship, BlockPos originShipLocal, int carriageIndex) {
        Vector3d shipLocal = carriageCentreShipLocal(originShipLocal, carriageIndex, 2);
        BlockPos worldPos = toWorldBlockPos(ship, shipLocal);
        level.setBlock(
            worldPos,
            Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true),
            3
        );
        BlockEntity be = level.getBlockEntity(worldPos);
        if (be instanceof LecternBlockEntity lectern) {
            lectern.setBook(buildBook(level.random));
            lectern.setChanged();
            LOGGER.debug("[DungeonTrain] Placed NARRATIVE lectern at {} (carriage {})", worldPos, carriageIndex);
        } else {
            LOGGER.warn("[DungeonTrain] NARRATIVE: expected lectern BE at {}, got {}", worldPos, be);
        }
    }

    private static void spawnEnemies(ServerLevel level, Ship ship, BlockPos originShipLocal, int carriageIndex) {
        RandomSource rng = level.random;
        int count = 2 + rng.nextInt(3);
        for (int i = 0; i < count; i++) {
            double offsetX = 1.5 + rng.nextDouble() * (CarriageTemplate.LENGTH - 3);
            double offsetZ = 1.5 + rng.nextDouble() * (CarriageTemplate.WIDTH - 3);
            // Y = origin.Y + 2.1 so zombies spawn solidly above the floor block
            // (which sits at origin.Y). +1.1 was observed to land them under the
            // train on a moving VS ship due to a 1-block Y offset in the
            // shipyard→world transform.
            Vector3d shipLocal = new Vector3d(
                originShipLocal.getX() + carriageIndex * CarriageTemplate.LENGTH + offsetX,
                originShipLocal.getY() + 2.1,
                originShipLocal.getZ() + offsetZ
            );
            Vector3d world = transform(ship, shipLocal);

            Zombie z = EntityType.ZOMBIE.create(level);
            if (z == null) continue;
            z.moveTo(world.x, world.y, world.z, rng.nextFloat() * 360f, 0f);
            z.addTag(TAG_CONTENT);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
        }
        LOGGER.debug("[DungeonTrain] Spawned {} ENEMIES in carriage {}", count, carriageIndex);
    }

    private static Vector3d carriageCentreShipLocal(BlockPos originShipLocal, int carriageIndex, int yOffset) {
        return new Vector3d(
            originShipLocal.getX() + carriageIndex * CarriageTemplate.LENGTH + CarriageTemplate.LENGTH / 2.0,
            originShipLocal.getY() + yOffset + 0.5,
            originShipLocal.getZ() + CarriageTemplate.WIDTH / 2.0
        );
    }

    private static Vector3d transform(Ship ship, Vector3d shipLocal) {
        Matrix4dc m = ship.getShipToWorld();
        Vector3d out = new Vector3d();
        m.transformPosition(shipLocal, out);
        return out;
    }

    private static BlockPos toWorldBlockPos(Ship ship, Vector3d shipLocal) {
        Vector3d w = transform(ship, shipLocal);
        return BlockPos.containing(w.x, w.y, w.z);
    }

    private static ItemStack buildBook(RandomSource rng) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", "Fragment");
        tag.putString("author", "Unknown");
        ListTag pages = new ListTag();
        int pageCount = 1 + rng.nextInt(2);
        for (int i = 0; i < pageCount; i++) {
            pages.add(StringTag.valueOf(BOOK_SNIPPETS[rng.nextInt(BOOK_SNIPPETS.length)]));
        }
        tag.put("pages", pages);
        return book;
    }
}
