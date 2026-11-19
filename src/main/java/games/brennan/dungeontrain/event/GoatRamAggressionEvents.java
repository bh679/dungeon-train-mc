package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.GoatRamScaling;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.goat.Goat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Optional;

/**
 * Train goats headbutt more often and harder the further along the train they spawn, on the
 * {@link GoatRamScaling} curve: vanilla until the first Biomes O' Plenty overworld stretch, then
 * shorter waits between rams and bigger hits.
 *
 * <p>At spawn a DT carriage goat (one carrying {@link CarriageContentsPlacer#NBT_SPAWN_CARRIAGE_PIDX})
 * past BoP gets its average wait stored in persistent data ({@link #WAIT_KEY}, so it survives reloads)
 * and a permanent attack-damage modifier. Each tick, when vanilla resets the goat's
 * {@link MemoryModuleType#RAM_COOLDOWN_TICKS} (after a ram, or a failed ram prep) to its own
 * 30 s – 5 min range, the cooldown is resampled into this goat's range instead. Goats spawned
 * before BoP, wild goats, and goats placed before this existed carry no key and stay vanilla.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class GoatRamAggressionEvents {

    /** Persistent-data key: this goat's average ticks between rams. Absent = vanilla goat. */
    public static final String WAIT_KEY = "dungeontrain_goat_ram_wait";

    private static final ResourceLocation DAMAGE_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "goat_ram_depth");

    /** The cycle the cached layout was derived from; rebuilt when config hands out a new cycle. */
    private static volatile WorldGenCycle cachedCycle;
    private static volatile GoatRamScaling.Layout cachedLayout;

    private GoatRamAggressionEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (event.loadedFromDisk()) return;
        if (!(event.getEntity() instanceof Goat goat)) return;
        if (!DungeonTrainConfig.getDifficultyEnabled()) return;

        CompoundTag persistent = goat.getPersistentData();
        if (!persistent.contains(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX)) return;
        if (persistent.contains(WAIT_KEY)) return;

        int carriageLength = DungeonTrainWorldData.get(serverLevel.getServer().overworld()).dims().length();
        long x = (long) persistent.getInt(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX) * carriageLength;
        GoatRamScaling.Layout layout = layout();
        if (!GoatRamScaling.isScaled(x, layout)) return;

        int averageTicks = GoatRamScaling.averageWaitTicks(x, layout);
        persistent.putInt(WAIT_KEY, averageTicks);
        addDamageBonus(goat, GoatRamScaling.bonusDamage(x, layout));
        resampleCooldown(goat, averageTicks);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Goat goat)) return;
        if (goat.level().isClientSide()) return;
        int averageTicks = goat.getPersistentData().getInt(WAIT_KEY);
        if (averageTicks <= 0) return;

        Optional<Integer> cooldown = goat.getBrain().getMemory(MemoryModuleType.RAM_COOLDOWN_TICKS);
        if (cooldown.isPresent() && cooldown.get() > GoatRamScaling.maxCooldownTicks(averageTicks)) {
            resampleCooldown(goat, averageTicks);
        }
    }

    private static void addDamageBonus(Goat goat, double bonus) {
        if (bonus <= 0.0) return;
        AttributeInstance attackDamage = goat.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage == null || attackDamage.hasModifier(DAMAGE_MODIFIER_ID)) return;
        attackDamage.addPermanentModifier(
                new AttributeModifier(DAMAGE_MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
    }

    private static void resampleCooldown(Goat goat, int averageTicks) {
        int min = GoatRamScaling.minCooldownTicks(averageTicks);
        int max = GoatRamScaling.maxCooldownTicks(averageTicks);
        goat.getBrain().setMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, min + goat.getRandom().nextInt(max - min + 1));
    }

    /** The curve's layout for the live world-gen cycle; {@code null} when it has no BoP overworld stretch. */
    private static GoatRamScaling.Layout layout() {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle != cachedCycle) {
            cachedLayout = deriveLayout(cycle);
            cachedCycle = cycle;
        }
        return cachedLayout;
    }

    private static GoatRamScaling.Layout deriveLayout(WorldGenCycle cycle) {
        for (long[] gap : cycle.overworldGapRanges(1)) {
            if (SecondLapOverworld.at(cycle, (int) gap[0]) == SecondLapOverworld.Stretch.BOP) {
                return GoatRamScaling.Layout.of(gap[0], cycle.startX(), cycle.period());
            }
        }
        return null;
    }
}
