package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

/**
 * Brings back the Killer Bunny: a rare slice of freshly spawned rabbits — on the
 * train or anywhere in the world — turn into vanilla's hostile
 * {@link Rabbit.Variant#EVIL} variant, at {@link DungeonTrainConfig#getKillerBunnyChance()}.
 *
 * <p>Vanilla still ships the variant but no longer spawns it naturally.
 * {@code Rabbit#setVariant(EVIL)} does all the work: attack + target goals
 * (players and wolves), armour, +5 attack damage and the hostile sounds. It
 * <strong>adds goals on every call without removing them</strong>, so it must run
 * exactly once per rabbit — hence the {@code loadedFromDisk} skip (a saved killer
 * bunny re-applies itself in {@code readAdditionalSaveData}) and the
 * {@link #ROLLED_TAG} one-shot, the same idiom as {@link TechnobladePigEvents}.</p>
 *
 * <p>Ordering is safe: this event fires after {@code finalizeSpawn} (natural
 * spawns and {@code CarriageContentsPlacer.spawnVariantMob} alike), so the
 * vanilla biome variant roll can't overwrite EVIL afterwards.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KillerBunnyEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Scoreboard tag marking a rabbit whose roll has already happened — win or
     * lose. Persists across saves, so a rabbit never gets a second roll.
     */
    public static final String ROLLED_TAG = "dungeontrain_killer_bunny_rolled";

    private static final String KILLER_BUNNY_NAME_KEY = "entity.minecraft.killer_bunny";

    private KillerBunnyEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        // Fresh spawns only — a reload must never re-roll (or re-apply the goals).
        if (event.loadedFromDisk()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Rabbit rabbit)) return;
        if (rabbit.getVariant() == Rabbit.Variant.EVIL) return;

        if (rabbit.getTags().contains(ROLLED_TAG)) return;
        rabbit.addTag(ROLLED_TAG);

        float roll = rabbit.getRandom().nextFloat();
        double chance = DungeonTrainConfig.getKillerBunnyChance();
        // Cheap roll first: the onboarding lookup scans online players, so only pay for it on a win.
        if (roll >= chance) return;

        boolean peaceful = serverLevel.getDifficulty() == Difficulty.PEACEFUL;
        boolean noHostiles = DifficultyProgression.onboardingStageFor(serverLevel)
                == DifficultyProgression.OnboardingStage.NO_HOSTILES;
        if (!shouldTurnKiller(roll, chance, peaceful, noHostiles)) return;

        rabbit.setVariant(Rabbit.Variant.EVIL);
        // Vanilla only names an unnamed rabbit; force it so an AIN fantasy name can't hide the joke.
        rabbit.setCustomName(Component.translatable(KILLER_BUNNY_NAME_KEY));
        LOGGER.debug("[DungeonTrain] Killer Bunny spawned at {} in {}",
                rabbit.blockPosition(), serverLevel.dimension().location());
    }

    /**
     * Pure roll: a rabbit turns killer when {@code roll < chance}, except on
     * Peaceful or during the onboarding no-hostiles stretch, where the promise of
     * "no hostiles yet" wins. Package-visible for unit tests.
     */
    static boolean shouldTurnKiller(float roll, double chance, boolean peaceful, boolean onboardingNoHostiles) {
        if (peaceful || onboardingNoHostiles) return false;
        return roll < chance;
    }
}
