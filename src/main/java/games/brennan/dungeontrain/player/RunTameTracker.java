package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * The single place a tamed animal is credited to a run — called from the two tame mixins
 * ({@code TamableAnimalTameMixin}, {@code AbstractHorseTameMixin}) so neither has to know about
 * data attachments, and so the tally has one entry point to test and to change.
 */
public final class RunTameTracker {

    private RunTameTracker() {}

    /** Credit {@code animal} to {@code player}'s run. Server-thread only; never throws. */
    public static void record(ServerPlayer player, Entity animal) {
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get())
            .recordTame(EntityType.getKey(animal.getType()));
    }
}
