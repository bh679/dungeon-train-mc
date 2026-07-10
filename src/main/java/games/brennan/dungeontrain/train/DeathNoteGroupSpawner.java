package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * At carriage-group generation, spawns a Death Note echo into any carriage whose index is the death
 * carriage of a downloaded curse targeting an online player. Invoked from
 * {@code TrainCarriageAppender.firePendingContentsEntitySpawns} beside {@link PlayerMobGroupSpawner},
 * so the echo is placed "ahead" the instant the target's death carriage generates.
 *
 * <p>Fires each note once: on a successful spawn it drops the note from the local pool and marks it
 * used on the relay. A player with no downloaded notes is skipped cheaply ({@code hasAny}), so this
 * per-group scan is near-free in the common case (almost nobody is cursed).</p>
 */
public final class DeathNoteGroupSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathNoteGroupSpawner() {}

    public static void maybeSpawnForGroup(ServerLevel level, PendingContentsEntitySpawn[] pending) {
        if (pending == null) return;
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        for (PendingContentsEntitySpawn p : pending) {
            if (p == null) continue;
            int carriageIdx = p.carriageIndex();
            for (ServerPlayer player : players) {
                UUID targetUuid = player.getUUID();
                if (!DeathNotePool.hasAny(targetUuid)) continue;
                for (DeathNotePool.Note note : DeathNotePool.matchesAt(targetUuid, carriageIdx)) {
                    BlockPos floorPos = PlayerMobGroupSpawner.interiorFloorCentre(p.shipyardOrigin(), p.dims());
                    boolean ok = DeathNoteEchoSpawner.spawn(level, floorPos, carriageIdx, note, targetUuid);
                    if (!ok) continue;
                    DeathNotePool.remove(targetUuid, note.id());   // never respawn locally
                    DeathNoteReporter.markUsed(note.id());         // fires-once on the relay
                    LOGGER.info("[DungeonTrain] DeathNote: echo for {} armed at carriage {} (note {})",
                        player.getGameProfile().getName(), carriageIdx, note.id());
                }
            }
        }
    }
}
