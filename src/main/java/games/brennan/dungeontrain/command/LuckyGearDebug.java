package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.LuckyGear;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * {@code /dungeontrain debug lucky-gear <level> [count] [entity]}: spawns {@code count} mobs of
 * {@code entity} (default zombie) off-world, runs the real carriage difficulty pass on each as if the
 * players had reached difficulty {@code level}, and reports how many rolled lucky gear
 * ({@link games.brennan.dungeontrain.difficulty.LuckyGear}) and the armor and mainhand damage of lucky
 * vs normal mobs. Nothing is added to the world. One {@code [DT-lucky-gear]} INFO line per run, for RCON.
 */
final class LuckyGearDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_COUNT = 1000;
    private static final int MAX_COUNT = 20000;
    private static final String DEFAULT_ENTITY = "minecraft:zombie";

    private LuckyGearDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("lucky-gear")
            .then(Commands.argument("level", IntegerArgumentType.integer(0, 10000))
                .executes(ctx -> run(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"),
                    DEFAULT_COUNT, DEFAULT_ENTITY))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                    .executes(ctx -> run(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"),
                        IntegerArgumentType.getInteger(ctx, "count"), DEFAULT_ENTITY))
                    .then(Commands.argument("entity", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"),
                            IntegerArgumentType.getInteger(ctx, "count"),
                            StringArgumentType.getString(ctx, "entity"))))));
    }

    /** Running totals for one group of mobs (lucky or normal). */
    private static final class Tally {
        int mobs;
        double armorSum;
        double armorMax;
        double damageSum;
        int pieces;
        int luckyDropPieces;

        void add(Mob mob) {
            mobs++;
            double armor = 0.0;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = mob.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) armor += amount(stack, Attributes.ARMOR.value());
            }
            armorSum += armor;
            armorMax = Math.max(armorMax, armor);
            damageSum += amount(mob.getItemBySlot(EquipmentSlot.MAINHAND), Attributes.ATTACK_DAMAGE.value());
            // Drop chances are protected on Mob; read them back the way the save does.
            CompoundTag saved = mob.saveWithoutId(new CompoundTag());
            countDrops(mob, saved.getList("ArmorDropChances", Tag.TAG_FLOAT),
                new EquipmentSlot[] {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD});
            countDrops(mob, saved.getList("HandDropChances", Tag.TAG_FLOAT),
                new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        }

        private void countDrops(Mob mob, ListTag chances, EquipmentSlot[] slots) {
            for (int i = 0; i < slots.length && i < chances.size(); i++) {
                if (mob.getItemBySlot(slots[i]).isEmpty()) continue;
                pieces++;
                if (Math.abs(chances.getFloat(i) - LuckyGear.LUCKY_DROP_CHANCE) < 1.0e-4) luckyDropPieces++;
            }
        }

        String describe() {
            if (mobs == 0) return "0";
            return String.format("%d mobs, armor avg %.1f max %.1f, mainhand damage avg %.1f, %d/%d pieces drop at 25%%",
                mobs, armorSum / mobs, armorMax, damageSum / mobs, luckyDropPieces, pieces);
        }
    }

    private static double amount(ItemStack stack, Attribute attribute) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0.0;
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
            if (entry.attribute().value() == attribute) sum += entry.modifier().amount();
        }
        return sum;
    }

    private static int run(CommandSourceStack source, int level, int count, String entityId) {
        ServerLevel serverLevel = source.getLevel();
        ResourceLocation id = ResourceLocation.tryParse(entityId.trim());
        Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (type.isEmpty()) {
            source.sendFailure(Component.literal("[DungeonTrain] Unknown entity: " + entityId));
            return 0;
        }
        // Travelled carriages that put the players at exactly `level` (DifficultyProgression#effectiveTier).
        int travelled = (level + Math.max(0, DungeonTrainConfig.getProgressionLevelDelay()))
            * Math.max(1, DungeonTrainConfig.getCarriagesPerTier());
        BlockPos pos = BlockPos.containing(source.getPosition());
        RandomSource rng = RandomSource.create();
        Tally lucky = new Tally();
        Tally normal = new Tally();
        for (int i = 0; i < count; i++) {
            Entity entity = type.get().create(serverLevel);
            if (!(entity instanceof Mob mob)) {
                source.sendFailure(Component.literal("[DungeonTrain] Not a mob: " + entityId));
                return 0;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            mob.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
            DifficultyApplier.apply(mob, travelled, rng, true, DifficultyApplier.StatScaling.PAST_MATERIAL_CAP);
            (mob.getTags().contains(DifficultyApplier.LUCKY_TAG) ? lucky : normal).add(mob);
            mob.discard();
        }
        String summary = String.format("level %d, %s x%d: lucky %.1f%% (%s) | normal (%s)",
            level, id, count, 100.0 * lucky.mobs / count, lucky.describe(), normal.describe());
        LOGGER.info("[DT-lucky-gear] {}", summary);
        source.sendSuccess(() -> Component.literal("[DungeonTrain] " + summary).withStyle(ChatFormatting.GRAY), false);
        return lucky.mobs;
    }
}
