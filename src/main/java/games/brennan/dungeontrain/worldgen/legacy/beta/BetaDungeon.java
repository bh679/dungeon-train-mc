package games.brennan.dungeontrain.worldgen.legacy.beta;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Beta 1.7.3's dungeon: a cobblestone room with a mossy floor, a spawner (zombie twice as likely as
 * skeleton or spider) and up to two chests against a wall, stocked from Beta's own loot list — saddles,
 * bread, gunpowder, the odd golden apple or music disc.
 */
public final class BetaDungeon {

    private static final int HEIGHT = 3;

    private BetaDungeon() {}

    public static boolean place(BetaWorld world, Random rand, int x, int y, int z) {
        int rx = rand.nextInt(2) + 2;
        int rz = rand.nextInt(2) + 2;
        int openings = 0;
        for (int bx = x - rx - 1; bx <= x + rx + 1; bx++) {
            for (int by = y - 1; by <= y + HEIGHT + 1; by++) {
                for (int bz = z - rz - 1; bz <= z + rz + 1; bz++) {
                    boolean solid = world.isSolid(bx, by, bz);
                    if (by == y - 1 && !solid) return false;
                    if (by == y + HEIGHT + 1 && !solid) return false;
                    boolean wall = bx == x - rx - 1 || bx == x + rx + 1 || bz == z - rz - 1 || bz == z + rz + 1;
                    if (wall && by == y && world.isAir(bx, by, bz) && world.isAir(bx, by + 1, bz)) openings++;
                }
            }
        }
        if (openings < 1 || openings > 5) return false;

        BlockState air = Blocks.AIR.defaultBlockState();
        for (int bx = x - rx - 1; bx <= x + rx + 1; bx++) {
            for (int by = y + HEIGHT; by >= y - 1; by--) {
                for (int bz = z - rz - 1; bz <= z + rz + 1; bz++) {
                    boolean shell = bx == x - rx - 1 || by == y - 1 || bz == z - rz - 1
                            || bx == x + rx + 1 || by == y + HEIGHT + 1 || bz == z + rz + 1;
                    if (!shell) {
                        world.set(bx, by, bz, air);
                    } else if (by >= 0 && !world.isSolid(bx, by - 1, bz)) {
                        world.set(bx, by, bz, air);
                    } else if (world.isSolid(bx, by, bz)) {
                        boolean moss = by == y - 1 && rand.nextInt(4) != 0;
                        world.set(bx, by, bz, (moss ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState());
                    }
                }
            }
        }

        for (int c = 0; c < 2; c++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                int cx = x + rand.nextInt(rx * 2 + 1) - rx;
                int cz = z + rand.nextInt(rz * 2 + 1) - rz;
                if (!world.isAir(cx, y, cz)) continue;
                int walls = 0;
                if (world.isSolid(cx - 1, y, cz)) walls++;
                if (world.isSolid(cx + 1, y, cz)) walls++;
                if (world.isSolid(cx, y, cz - 1)) walls++;
                if (world.isSolid(cx, y, cz + 1)) walls++;
                if (walls != 1) continue;
                world.set(cx, y, cz, Blocks.CHEST.defaultBlockState());
                BlockEntity be = world.level().getBlockEntity(world.pos(cx, y, cz));
                if (be instanceof ChestBlockEntity chest) {
                    for (int i = 0; i < 8; i++) {
                        ItemStack item = loot(rand);
                        if (!item.isEmpty()) chest.setItem(rand.nextInt(chest.getContainerSize()), item);
                    }
                }
                break;
            }
        }

        world.set(x, y, z, Blocks.SPAWNER.defaultBlockState());
        if (world.level().getBlockEntity(world.pos(x, y, z)) instanceof SpawnerBlockEntity spawner) {
            spawner.setEntityId(mob(rand), RandomSource.create(rand.nextLong()));
        }
        return true;
    }

    private static ItemStack loot(Random rand) {
        return switch (rand.nextInt(11)) {
            case 0 -> new ItemStack(Items.SADDLE);
            case 1 -> new ItemStack(Items.IRON_INGOT, rand.nextInt(4) + 1);
            case 2 -> new ItemStack(Items.BREAD);
            case 3 -> new ItemStack(Items.WHEAT, rand.nextInt(4) + 1);
            case 4 -> new ItemStack(Items.GUNPOWDER, rand.nextInt(4) + 1);
            case 5 -> new ItemStack(Items.STRING, rand.nextInt(4) + 1);
            case 6 -> new ItemStack(Items.BUCKET);
            case 7 -> rand.nextInt(100) == 0 ? new ItemStack(Items.GOLDEN_APPLE) : ItemStack.EMPTY;
            case 8 -> rand.nextInt(2) == 0 ? new ItemStack(Items.REDSTONE, rand.nextInt(4) + 1) : ItemStack.EMPTY;
            case 9 -> rand.nextInt(10) == 0
                    ? new ItemStack(rand.nextInt(2) == 0 ? Items.MUSIC_DISC_13 : Items.MUSIC_DISC_CAT)
                    : ItemStack.EMPTY;
            case 10 -> new ItemStack(Items.COCOA_BEANS);
            default -> ItemStack.EMPTY;
        };
    }

    private static EntityType<?> mob(Random rand) {
        int i = rand.nextInt(4);
        return i == 0 ? EntityType.SKELETON : i == 3 ? EntityType.SPIDER : EntityType.ZOMBIE;
    }
}
