package games.brennan.dungeontrain.event;

import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainMembership;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Hostile mobs on the train get Adventure Item Names' hostile naming roll (3% by default, an
 * always-visible name), the way {@link VillagerTrainSpawnEvents} bridges villager naming.
 *
 * <p>Why a bridge: AIN rolls in its {@code Mob#finalizeSpawn} mixin, but many carriage mobs are
 * created from NBT and never run {@code finalizeSpawn}, and where they do the carriage-contents
 * tag that makes {@link TrainMembership#isOnTrain} true may not be on yet. So the roll happens
 * again here, when the mob joins the level. AIN tags a hostile after its one roll
 * ({@link #NAME_ROLLED_TAG}), so a mob reached by both paths, or reloaded, never rolls twice.
 * An AIN without hostile naming simply ignores the call.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HostileTrainNameEvents {

    /**
     * AIN's roll-once marker ({@code NameComposer.HOSTILE_NAME_ROLLED_TAG}). Spelled out rather
     * than referenced so DT still builds and runs against AIN releases older than the field.
     */
    public static final String NAME_ROLLED_TAG = "adventureitemnames_name_rolled";

    private HostileTrainNameEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk()) return;
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(mob instanceof Enemy)) return;
        if (!TrainMembership.isOnTrain(mob)) return;
        NameComposer.applyMobName(mob, mob.getRandom());
    }

    /**
     * Strip an AIN-rolled hostile name off entity NBT captured into a carriage template. The
     * editor saves live NBT, so without this a named zombie standing in the editor would stamp
     * the same name, or a permanent "already rolled" marker, into every carriage using the
     * template. Only names AIN gave (the mob carries {@link #NAME_ROLLED_TAG}) are removed: a
     * name the author set by hand has no marker and is kept.
     */
    public static void stripBakedHostileName(CompoundTag entityNbt) {
        if (!entityNbt.contains("Tags", Tag.TAG_LIST)) return;
        ListTag tags = entityNbt.getList("Tags", Tag.TAG_STRING);
        if (!tags.removeIf(t -> NAME_ROLLED_TAG.equals(t.getAsString()))) return;
        entityNbt.remove("CustomName");
        entityNbt.remove("CustomNameVisible");
        if (tags.isEmpty()) {
            entityNbt.remove("Tags");
        } else {
            entityNbt.put("Tags", tags);
        }
    }
}
