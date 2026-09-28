package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.MobExperienceScaling;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

/**
 * Pays out more experience for a monster that was genuinely harder to kill — one wearing
 * high-level armor or a strong weapon, carrying deep enchantments, or running beneficial potion
 * effects. All three are things {@code DifficultyApplier} hands out as the train progresses, and
 * vanilla rewards none of them.
 *
 * <p>The whole judgement lives in {@link MobExperienceScaling#scaledXp(net.minecraft.world.entity.LivingEntity, int)},
 * which scores the mob off its live equipment and active effects. This class only wires it to the
 * drop.</p>
 *
 * <p>{@link Mob}s only: players (whose drop is a separate keep-inventory concern, see
 * {@code LoadoutStore}) and bare {@code LivingEntity}s such as armor stands are left alone. The
 * event already fires only for a player-attributed kill with experience to give, so a mob that
 * drops nothing keeps dropping nothing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MobExperienceEvents {

    private MobExperienceEvents() {}

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;

        int base = event.getDroppedExperience();
        int scaled = MobExperienceScaling.scaledXp(mob, base);
        if (scaled != base) event.setDroppedExperience(scaled);
    }
}
