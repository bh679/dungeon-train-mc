package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.TechnobladePigNames;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Rare tribute easter egg: a small slice of the pigs that Adventure Item Names
 * (AIN) has just named get a Technoblade name instead of AIN's generic fantasy
 * composition.
 *
 * <p><strong>DT never names a pig itself.</strong> This handler only reacts to a
 * name AIN already applied — the {@code hasCustomName} check below is the whole
 * "when pigs are named" gate. Unnamed pigs are left completely alone, so the
 * effective rate is {@link #TECHNOBLADE_CHANCE} <em>of AIN's own</em> 5%
 * {@code MOB_PASSIVE} roll: roughly one pig in four hundred.</p>
 *
 * <p>Ordering is safe. AIN names via a mixin on {@code Mob#finalizeSpawn}, and
 * {@code CarriageContentsPlacer.spawnVariantMob} calls {@code finalizeSpawn}
 * <em>before</em> {@code addFreshEntity} — so by the time this event fires the
 * name is already set. Pigs from the NBT-template spawn path never call
 * {@code finalizeSpawn} at all, so they are unnamed and simply don't
 * participate; that is the same gap documented for villagers in
 * {@link VillagerTrainSpawnEvents}.</p>
 *
 * <p>No train-membership gate is needed: AIN's ambient mob naming is already
 * restricted to on-train mobs by the gate DT registers in
 * {@code DungeonTrain.commonSetup}, so any named pig reaching this handler is a
 * train pig in practice.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TechnobladePigEvents {

    /**
     * Chance that an already-named pig has its name replaced with a tribute
     * name. Conditional on AIN having named the pig at all.
     */
    private static final float TECHNOBLADE_CHANCE = 0.05f;

    /**
     * Scoreboard tag marking a pig whose roll has already happened — win or
     * lose. Scoreboard tags persist across saves, so a pig that rolled and lost
     * never gets a second chance on a later join. Same idiom as
     * {@link VillagerTrainSpawnEvents#REROLLED_TAG}.
     */
    public static final String ROLLED_TAG = "dungeontrain_techno_pig_rolled";

    private TechnobladePigEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        // Fresh spawns only. Without this a reload would re-roll every pig on
        // the train each time its chunk loads, and the easter egg would creep
        // from "rare" to "eventually everywhere".
        if (event.loadedFromDisk()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Pig pig)) return;

        // The "when pigs are named" gate — see the class note.
        if (!pig.hasCustomName()) return;

        if (pig.getTags().contains(ROLLED_TAG)) return;
        pig.addTag(ROLLED_TAG);

        RandomSource rng = pig.getRandom();
        if (rng.nextFloat() >= TECHNOBLADE_CHANCE) return;

        pig.setCustomName(Component.literal(TechnobladePigNames.pick(rng)));
    }
}
