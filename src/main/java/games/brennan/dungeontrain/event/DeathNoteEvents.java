package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.PendingDeathNotes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Server-side lifecycle for the Death Note curse: when a player who has signed one or more Death
 * Notes dies, each pending curse is armed with the carriage they died at + this world's key and
 * uploaded to the relay for the target to download. The download side + echo spawn live in
 * {@code DeathNoteRefreshEvents} / {@code DeathNoteGroupSpawner} / {@code DeathNoteEchoController}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathNoteEvents() {}

    /**
     * A player death arms their pending Death Notes: each is stamped with the carriage they died at
     * (the "where the author died" the target must reach) and uploaded — provided the author granted
     * network consent. Off-train deaths (no carriage) and non-consenting authors drop the note; the
     * echo is of a <em>dead</em> player, so an author who never dies never curses anyone.
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();

        // Take (and remove) this author's pending notes — arming happens once, on this death.
        List<PendingDeathNotes.PendingDeathNote> pending =
                PendingDeathNotes.get(level).takeForAuthor(player.getUUID());
        if (pending.isEmpty()) return;

        boolean canSync = DeathNoteGate.canSync(player);
        Integer deathCarriage = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
        String worldKey = String.valueOf(DungeonTrainWorldData.get(level).getGenerationSeed());

        for (PendingDeathNotes.PendingDeathNote note : pending) {
            if (deathCarriage == null) {
                LOGGER.debug("[DungeonTrain] DeathNote: {} died off-train — curse on {} dropped (no carriage).",
                        note.authorName(), note.targetName());
                continue;
            }
            if (!canSync) {
                LOGGER.debug("[DungeonTrain] DeathNote: curse on {} not synced (feature off or no consent).",
                        note.targetName());
                continue;
            }
            DeathNoteReporter.submit(player.getUUID(), note.authorName(), note.targetName(),
                    note.targetUuid(), deathCarriage, worldKey, "");
        }
    }
}
