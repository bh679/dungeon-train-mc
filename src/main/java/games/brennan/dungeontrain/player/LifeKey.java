package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The id of the life a player is on — what the relay's one-life boards key a life by.
 *
 * <p>Kept in {@link ModDataAttachments#PHOTO_LIFE_ID}, which is not copied on death, so a new life
 * gets a new id. Minted on first ask. The photo boards and the Live Feed boards share it, so the
 * relay sees one life as one life whichever board it is scoring.</p>
 */
public final class LifeKey {

    private LifeKey() {}

    /** This life's id, minting one if the life has none yet. 32 lowercase hex characters. */
    public static String of(ServerPlayer player) {
        String id = player.getData(ModDataAttachments.PHOTO_LIFE_ID.get());
        if (id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "");
            player.setData(ModDataAttachments.PHOTO_LIFE_ID.get(), id);
        }
        return id;
    }
}
