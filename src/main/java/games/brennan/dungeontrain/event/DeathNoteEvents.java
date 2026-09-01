package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.narrative.DeathNoteSigning;
import games.brennan.dungeontrain.narrative.NoteKind;
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

        // Tag the curse with the author's Free Play (cheated / sandbox) state at death. The curse is
        // still uploaded either way; the receive side (DeathNoteRefreshEvents.spawnArrivedEchoes)
        // spawns it only for a target whose Free Play state matches — so a Free Play curse haunts
        // only Free Play runs and a legit curse only legit runs.
        boolean freePlay = RunIntegrity.isCheated(player);

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
                    note.targetUuid(), deathCarriage, worldKey, "", freePlay, note.kind(), note.lines());
        }
    }

    /**
     * A note echo drops the note itself when it dies — a keepable trophy book (black for a Death
     * Note, pink for a Love Note; neither burns). Identified by the {@code KEY_TARGET}
     * persistent-data marker so ordinary PlayerMobs are unaffected.
     */
    @SubscribeEvent
    public static void onEchoDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PlayerMobEntity echo)) return;
        if (echo.level().isClientSide()) return;
        CompoundTag data = echo.getPersistentData();
        if (!data.contains(DeathNoteEchoSpawner.KEY_TARGET)) return; // not a note echo
        DeathNoteEchoController.unregister(echo.getUUID());
        String author = data.contains(DeathNoteEchoSpawner.KEY_AUTHOR)
                ? data.getString(DeathNoteEchoSpawner.KEY_AUTHOR) : "Unknown";
        NoteKind kind = kindOf(data);
        // The note landed but the echo did not survive it — tell the relay so the author's story
        // book knows how it ended. Write-once relay-side, so whoever landed the killing blow, this
        // is the ending.
        reportOutcome(echo, data, DeathNoteReporter.OUTCOME_TARGET_KILLED_ECHO);
        ItemStack book = DeathNoteSigning.buildTrophyBook(author, kind);
        ItemEntity drop = new ItemEntity(echo.level(), echo.getX(), echo.getY() + 0.5, echo.getZ(), book);
        drop.setDefaultPickUpDelay();
        echo.level().addFreshEntity(drop);
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(killer, killedEchoActionId(kind));
        }
        LOGGER.debug("[DungeonTrain] Note: echo of {} dropped a {} on death", author, kind.trophyTitle());
    }

    /** The {@link NoteKind} stamped on an echo; {@link NoteKind#DEATH} for echoes predating the marker. */
    private static NoteKind kindOf(CompoundTag echoData) {
        return NoteKind.fromId(echoData.getString(DeathNoteEchoSpawner.KEY_KIND));
    }

    /**
     * The other half of a Love Note: the cursed — beloved — player gives something <em>back</em> to
     * the echo that came to find them. Grants the "Loved Back" advancement.
     *
     * <p>Called from {@code PlayerMobSocialBridge} off PlayerMob's gift seam, which already fires
     * whenever a player hands any PlayerMob an item; this just filters that stream down to the one
     * exchange worth rewarding. Three conditions, all required:</p>
     * <ul>
     *   <li>the mob is a note echo carrying {@link DeathNoteEchoSpawner#KEY_TARGET};</li>
     *   <li>it is a {@link NoteKind#LOVE} echo — gifting a curse's echo is not this story;</li>
     *   <li>the giver <em>is</em> that note's target. The advancement means "you gave back to the
     *       one who came to love you", not "a bystander threw an item at it".</li>
     * </ul>
     *
     * <p>Reads the kind off the entity's persistent data rather than {@code DeathNoteEchoController}'s
     * live map, so an echo that has been saved and reloaded still counts. No-throw: the gift seam is
     * best-effort compat, and a failure here must never break the gift itself.</p>
     */
    public static void onPlayerGiftedEcho(ServerPlayer giver, UUID echoUuid) {
        try {
            if (giver == null || echoUuid == null) return;
            if (!(giver.level() instanceof ServerLevel level)) return;
            if (!(level.getEntity(echoUuid) instanceof PlayerMobEntity echo)) return;
            CompoundTag data = echo.getPersistentData();
            if (!data.contains(DeathNoteEchoSpawner.KEY_TARGET)) return;      // an ordinary PlayerMob
            if (kindOf(data) != NoteKind.LOVE) return;                        // a curse's echo — not this
            if (!giver.getUUID().toString().equals(data.getString(DeathNoteEchoSpawner.KEY_TARGET))) {
                return;                                                       // a bystander, not the beloved
            }
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(giver, "gifted_love_note_echo");
            LOGGER.debug("[DungeonTrain] LoveNote: {} gave a gift back to the echo that came for them",
                    giver.getName().getString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] LoveNote: gift-back check failed: {}", t.toString());
        }
    }

    /** Advancement action id for killing an echo of {@code kind}. */
    private static String killedEchoActionId(NoteKind kind) {
        return kind == NoteKind.LOVE ? "killed_love_note_echo" : "killed_death_note_echo";
    }

    /**
     * The other ending: a cursed player killed BY the echo hunting them. Reported against the same
     * relay note so the author's story book can say the curse ran its course.
     *
     * <p>Read off the damage source's owning entity (not the direct entity) so an echo's arrow still
     * counts as the echo's kill, and gated on the echo's {@code KEY_TARGET} matching the player who
     * died — an echo that happens to kill a bystander is not that bystander's curse.</p>
     */
    @SubscribeEvent
    public static void onCursedPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (victim.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof PlayerMobEntity echo)) return;
        CompoundTag data = echo.getPersistentData();
        if (!data.contains(DeathNoteEchoSpawner.KEY_TARGET)) return;      // an ordinary PlayerMob kill
        if (!victim.getUUID().toString().equals(data.getString(DeathNoteEchoSpawner.KEY_TARGET))) return;
        reportOutcome(echo, data, DeathNoteReporter.OUTCOME_ECHO_KILLED_TARGET);
        LOGGER.debug("[DungeonTrain] DeathNote: echo killed its target {}", victim.getName().getString());
    }

    /**
     * Report {@code outcome} for the curse stamped on {@code echo}'s persistent data, if it is both
     * reportable and permitted:
     * <ul>
     *   <li>the echo carries a relay note id — dev-spawned echoes ({@code /dtechotest deathnote}) and
     *       echoes from before this stamp existed have no note to report against;</li>
     *   <li>the cursed target is online and their client granted relay consent
     *       ({@link DeathNoteGate#canSync}) — this report leaves their game, so it rides the same
     *       fail-closed gate as the download that delivered the curse in the first place.</li>
     * </ul>
     */
    private static void reportOutcome(PlayerMobEntity echo, CompoundTag echoData, String outcome) {
        int noteId = echoData.contains(DeathNoteEchoSpawner.KEY_NOTE_ID)
                ? echoData.getInt(DeathNoteEchoSpawner.KEY_NOTE_ID) : 0;
        if (noteId <= 0) return;
        if (echo.level().getServer() == null) return;
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(echoData.getString(DeathNoteEchoSpawner.KEY_TARGET));
        } catch (IllegalArgumentException e) {
            return;
        }
        ServerPlayer target = echo.level().getServer().getPlayerList().getPlayer(targetUuid);
        if (target == null || !DeathNoteGate.canSync(target)) return;
        DeathNoteReporter.reportOutcome(noteId, outcome);
    }
}
