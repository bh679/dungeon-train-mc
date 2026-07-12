package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.narrative.DeathNoteSigning;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.dungeontrain.world.PendingDeathNotes;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Server-side lifecycle for the Death Note curse: when a player who has signed one or more Death
 * Notes dies, each pending curse is armed with the carriage they died at + this world's key and
 * uploaded to the relay for the target to download. The download side + echo spawn live in
 * {@code DeathNoteRefreshEvents} / {@code DeathNoteGroupSpawner} / {@code DeathNoteEchoController}.
 */
public final class DeathNoteEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathNoteEvents() {}

    /**
     * A player death arms their pending Death Notes: each is stamped with the carriage they died at
     * (the "where the author died" the target must reach) and uploaded — provided the author granted
     * network consent. Off-train deaths (no carriage) and non-consenting authors drop the note; the
     * echo is of a <em>dead</em> player, so an author who never dies never curses anyone.
     */
        public static void onPlayerDeath(net.minecraft.world.entity.LivingEntity deadEntity, net.minecraft.world.damagesource.DamageSource deathSource, boolean deathCanceled) {
        if (!(deadEntity instanceof ServerPlayer player)) return;
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
            // Both dev + release upload to the relay (a global store). A curse must outlive the author's
            // death — which in this roguelike starts a brand-new world — so a per-world local store is
            // orphaned; the relay is pulled by the target in their next world (login + arrival scan).
            // worldKey is still sent (the relay requires it) but is no longer used to scope the pull.
            DeathNoteReporter.submit(player.getUUID(), note.authorName(), note.targetName(),
                    note.targetUuid(), deathCarriage, worldKey, "");
        }
    }

    /**
     * A death-note echo drops the Death Note itself when it dies — a keepable black "Death Note"
     * trophy book (does not soul-burn). Identified by the {@code KEY_TARGET} persistent-data marker
     * so ordinary PlayerMobs are unaffected.
     */
        public static void onEchoDeath(net.minecraft.world.entity.LivingEntity deadEntity, net.minecraft.world.damagesource.DamageSource deathSource, boolean deathCanceled) {
        if (!(deadEntity instanceof PlayerMobEntity echo)) return;
        if (echo.level().isClientSide()) return;
        CompoundTag data = echo.getPersistentData();
        if (!data.contains(DeathNoteEchoSpawner.KEY_TARGET)) return; // not a death-note echo
        DeathNoteEchoController.unregister(echo.getUUID());
        String author = data.contains(DeathNoteEchoSpawner.KEY_AUTHOR)
                ? data.getString(DeathNoteEchoSpawner.KEY_AUTHOR) : "Unknown";
        ItemStack book = DeathNoteSigning.buildTrophyBook(author);
        ItemEntity drop = new ItemEntity(echo.level(), echo.getX(), echo.getY() + 0.5, echo.getZ(), book);
        drop.setDefaultPickUpDelay();
        echo.level().addFreshEntity(drop);
        if (deathSource.getEntity() instanceof ServerPlayer killer) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(killer, "killed_death_note_echo");
        }
        LOGGER.debug("[DungeonTrain] DeathNote: echo of {} dropped a Death Note on death", author);
    }
}
