package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.LuckyBonusRoller;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.playermob.compat.TrainConfinement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.ContainerSingleItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Deeper Darker's Gloomslate Pots, when Dungeon Train rolled them as loot vases, have a one-in-four chance to
 * wake up as one of the mod's three pot mobs when a player breaks them. The pot's item goes into the mob
 * instead of spilling on the floor, and drops when the mob dies.
 *
 * <p>The roll happens at break time from the level's random, never ahead of it, so nothing on the block
 * (Jade, any block-info overlay, the save file) can tell a sleeping pot from a plain one.</p>
 *
 * <p>Only DT-rolled pots take part: those carry the vase Luck-bonus list
 * ({@link LuckyBonusRoller#hasBonus}), which a player-placed pot or one of the mod's own structure pots
 * never has. The bonus itself is untouched here and is still served by {@link LuckyLootEvents} on the same
 * break.</p>
 *
 * <p>{@link BlockEvent.BreakEvent} is the hook because it fires before the block is removed, while the pot's
 * block entity still holds its item; {@code GloomslatePotBlock.onRemove} spills whatever is left, so the item
 * is taken out first. Pots shattered by projectiles go straight to {@code destroyBlock} with no pre-removal
 * event and keep spilling as before.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class GloomslatePotMobEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chance that a broken DT loot pot becomes a pot mob. */
    public static final float CONVERT_CHANCE = 0.25f;

    /** Persistent-data key on the mob: the pot's item as an {@link ItemStack} tag. */
    public static final String NBT_POT_LOOT = "DungeonTrainPotLoot";

    /** Deeper Darker's three pot mobs, all {@code OvercastPot}. */
    static final List<ResourceLocation> POT_MOBS = List.of(
        ResourceLocation.fromNamespaceAndPath("deeperdarker", "anger_pot"),
        ResourceLocation.fromNamespaceAndPath("deeperdarker", "fear_pot"),
        ResourceLocation.fromNamespaceAndPath("deeperdarker", "sorrow_pot"));

    private static final double DROP_JITTER = 0.25;

    private GloomslatePotMobEvents() {}

    @SubscribeEvent
    public static void onPotBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockState state = event.getState();
        if (!ContainerContentsRoller.isGloomslatePot(state)) return;
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ContainerSingleItem pot)) return;
        if (!LuckyBonusRoller.hasBonus(be)) return;
        if (!shouldConvert(level.getRandom())) return;

        ItemStack held = pot.getTheItem().copy();
        pot.setTheItem(ItemStack.EMPTY);
        Mob mob = createPotMob(level, pos, player, held);
        if (mob == null || !level.addFreshEntity(mob)) {
            // Never lose the loot: put it back so the pot spills as usual.
            pot.setTheItem(held);
            LOGGER.warn("[DungeonTrain] Gloom pot at {} could not wake as a mob; spilling normally", pos);
            return;
        }
        LOGGER.debug("[DungeonTrain] Gloom pot at {} broken by {} woke as {} carrying {}",
            pos, player.getGameProfile().getName(), mob.getType().getDescriptionId(), held);
    }

    @SubscribeEvent
    public static void onPotMobDrops(LivingDropsEvent event) {
        LivingEntity mob = event.getEntity();
        if (!(mob.level() instanceof ServerLevel level)) return;
        Optional<ItemStack> loot = takeLoot(mob.getPersistentData(), level.registryAccess());
        if (loot.isEmpty() || loot.get().isEmpty()) return;
        event.getDrops().add(lootDrop(level, mob, loot.get()));
    }

    /** True when the pot wakes as a mob: a fresh roll under {@link #CONVERT_CHANCE}. */
    static boolean shouldConvert(RandomSource random) {
        return random.nextFloat() < CONVERT_CHANCE;
    }

    /** One of the three pot mobs, uniformly. */
    static ResourceLocation pickPotMob(RandomSource random) {
        return POT_MOBS.get(random.nextInt(POT_MOBS.size()));
    }

    /** Writes {@code held} under {@link #NBT_POT_LOOT}; an empty stack writes nothing. */
    static void storeLoot(CompoundTag persistent, ItemStack held, HolderLookup.Provider registries) {
        if (held.isEmpty()) return;
        persistent.put(NBT_POT_LOOT, held.save(registries));
    }

    /** Reads and removes the stored loot, if any. */
    static Optional<ItemStack> takeLoot(CompoundTag persistent, HolderLookup.Provider registries) {
        if (!persistent.contains(NBT_POT_LOOT, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag tag = persistent.getCompound(NBT_POT_LOOT);
        persistent.remove(NBT_POT_LOOT);
        return ItemStack.parse(registries, tag);
    }

    /**
     * The pot mob at the pot's position, facing the breaker, set up as carriage contents (contents tag +
     * spawn markers, which also let {@link MobDifficultyEvents} apply carriage difficulty on join) and
     * carrying the pot's item. Null when the entity type is missing or refuses to create.
     */
    private static Mob createPotMob(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack held) {
        ResourceLocation id = pickPotMob(level.getRandom());
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (type.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Pot mob {} is not registered", id);
            return null;
        }
        Entity created = type.get().create(level);
        if (!(created instanceof Mob mob)) return null;

        double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
        float yaw = Mth.wrapDegrees(player.getYRot() + 180.0f);
        mob.moveTo(x, y, z, yaw, 0.0f);
        mob.setYHeadRot(yaw);
        mob.setPersistenceRequired();

        int carriagePIdx = TrainConfinement.carriageIndex(player);
        mob.addTag(CarriageContentsPlacer.contentsTagFor(carriagePIdx));
        CompoundTag persistent = mob.getPersistentData();
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_X, x);
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Y, y);
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Z, z);
        persistent.putLong(CarriageContentsPlacer.NBT_SPAWN_GAME_TICK, level.getGameTime());
        persistent.putInt(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);
        storeLoot(persistent, held, level.registryAccess());
        return mob;
    }

    /** An item entity at the mob with the same small jitter vanilla gives drops. */
    private static ItemEntity lootDrop(ServerLevel level, LivingEntity mob, ItemStack stack) {
        RandomSource random = level.getRandom();
        double x = mob.getX() + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        double y = mob.getY() + 0.5 + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        double z = mob.getZ() + Mth.nextDouble(random, -DROP_JITTER, DROP_JITTER);
        ItemEntity drop = new ItemEntity(level, x, y, z, stack);
        drop.setDefaultPickUpDelay();
        return drop;
    }
}
