package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.event.DeathNoteEchoController;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.FeelingLedger;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Spawns the echo of a signed note's author into the carriage the author died at, aimed at that
 * note's target alone. Built like the dev echo command + {@link PlayerMobGroupSpawner}: a
 * {@code playermob:player_mob} finalized with {@code COMMAND} (so PlayerMob doesn't re-roll the
 * identity), the author's skin baked in ({@link SourceProfileSkin}), and difficulty-scaled gear.
 * Tagged as carriage contents (kill-ahead sweep spares it) and marked in persistent data so
 * {@code DeathNoteEchoController} can steer it toward the target.
 *
 * <p>The {@link NoteKind} decides the one thing that matters on arrival — the seeded feeling toward
 * the target, which {@code DispositionResolver} then reads as the echo's whole disposition:</p>
 * <ul>
 *   <li>{@link NoteKind#DEATH} → {@link FeelingLedger#MIN} (0), read as "hate": the echo hunts them
 *       regardless of its own nature. The difficulty gear also lands its FIGHT/FLEE self-assessment
 *       on FIGHT, so it arrives as an armed threat.</li>
 *   <li>{@link NoteKind#LOVE} → {@link FeelingLedger#MAX} (10), read as "love": the echo greets
 *       rather than attacks at any friendliness, gifts them items, and — since
 *       {@code defendIsWorthwhile} is deliberately independent of fight/flight — wades in to defend
 *       them unless hopelessly outmatched. The same gear now arms it <em>for</em> the target.</li>
 * </ul>
 */
public final class DeathNoteEchoSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation PLAYER_MOB_ID =
        ResourceLocation.fromNamespaceAndPath("playermob", "player_mob");

    /** Persistent-data marker: the target player's UUID (string) this echo was sent to. */
    public static final String KEY_TARGET = "dt_deathnote_target";
    /** Persistent-data marker: the relay note id this echo came from. */
    public static final String KEY_NOTE_ID = "dt_deathnote_id";
    /** Persistent-data marker: the author's name, for the trophy book the echo drops when it dies. */
    public static final String KEY_AUTHOR = "dt_deathnote_author";
    /**
     * Persistent-data marker: which {@link NoteKind} sent this echo. Absent on echoes spawned before
     * Love Notes existed, which {@link NoteKind#fromId} reads back as {@link NoteKind#DEATH}.
     */
    public static final String KEY_KIND = "dt_deathnote_kind";

    private DeathNoteEchoSpawner() {}

    /**
     * Spawn the echo of {@code note}'s author onto the carriage {@code target} is currently riding —
     * at the carriage's SHIPYARD interior floor so Sable binds it to the moving carriage. A world-
     * space position (e.g. the player's live coords) leaves the mob off the moving deck and invisible.
     * Returns false (leaving the note to retry next scan) if the target isn't on a resolvable
     * carriage group yet.
     *
     * <p>{@code noteId} is the relay note this echo came from; it is stamped into the echo's
     * persistent data ({@link #KEY_NOTE_ID}) so the outcome of the fight can be reported back
     * against the right curse — see {@code DeathNoteEvents}. Pass {@code 0} for an echo with no
     * relay note behind it (the dev {@code /dtechotest} spawn); no outcome is then reported.</p>
     */
    public static boolean spawnForTarget(ServerLevel level, ServerPlayer target,
                                         String authorUuid, String authorName, int deathCarriage,
                                         int noteId, NoteKind kind) {
        Trains.Carriage group = groupContaining(level, target);
        if (group == null) {
            LOGGER.debug("[DungeonTrain] DeathNote echo: {} not on a resolvable carriage group yet — deferring.",
                    target.getGameProfile().getName());
            return false;
        }
        TrainTransformProvider provider = group.provider();
        int anchor = provider.getPIdx();
        int groupSize = provider.getGroupSize();
        // Spawn in the player's own carriage; clamp into this group's range so the slot is valid.
        int pidx = TrainConfinement.carriageIndex(target);
        if (pidx < anchor || pidx >= anchor + groupSize) pidx = anchor;
        BlockPos floorPos = interiorFloorPos(provider, pidx);
        return spawn(level, floorPos, deathCarriage, authorUuid, authorName, target, noteId, kind);
    }

    /** The train group whose world AABB contains {@code player} (player position is world-space), or null. */
    private static Trains.Carriage groupContaining(ServerLevel level, ServerPlayer player) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            var bb = c.ship().worldAABB();
            if (bb == null) continue;
            if (x >= bb.minX() - 1.5 && x <= bb.maxX() + 1.5
                    && y >= bb.minY() - 1.5 && y <= bb.maxY() + 1.5
                    && z >= bb.minZ() - 1.5 && z <= bb.maxZ() + 1.5) {
                return c;
            }
        }
        return null;
    }

    /** Shipyard interior floor-centre of carriage {@code targetPIdx} within {@code provider}'s group. */
    private static BlockPos interiorFloorPos(TrainTransformProvider provider, int targetPIdx) {
        CarriageDims dims = provider.dims();
        int slot = targetPIdx - provider.getPIdx();
        int enclosedStartOffset = provider.getGroupSize() > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos carriageOrigin = provider.getShipyardOrigin()
                .offset(enclosedStartOffset + slot * dims.length(), 0, 0);
        return PlayerMobGroupSpawner.interiorFloorCentre(carriageOrigin, dims);
    }

    /**
     * Spawn the echo of {@code note}'s author at {@code floorPos} (carriage interior), disposed
     * toward {@code targetUuid} per {@code kind}. Returns true if the mob was added. No-throw — a
     * failure logs + returns false so the caller doesn't consume the note on a bad spawn.
     */
    private static boolean spawn(ServerLevel level, BlockPos floorPos, int carriagePIdx,
                                 String authorUuidStr, String authorNameStr, ServerPlayer target,
                                 int noteId, NoteKind kind) {
        UUID targetUuid = target.getUUID();
        try {
            Optional<EntityType<?>> typeOpt = EntityType.byString(PLAYER_MOB_ID.toString());
            if (typeOpt.isEmpty()) return false;
            Entity entity = typeOpt.get().create(level);
            if (!(entity instanceof PlayerMobEntity mob)) {
                if (entity != null) entity.discard();
                return false;
            }
            RandomSource rng = level.getRandom();
            mob.setUUID(UUID.randomUUID());
            Vec3 pos = Vec3.atBottomCenterOf(floorPos);
            mob.moveTo(pos.x, pos.y, pos.z, rng.nextFloat() * 360.0f, 0.0f);

            // COMMAND (not EVENT): PlayerMob rolls a body but does NOT run its own reincarnation and
            // overwrite the identity we bake below.
            try {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(floorPos), MobSpawnType.COMMAND, null);
            } catch (Throwable ignored) {
                // defaults are fine for the echo
            }

            // Bake the author's identity so this reads + registers as their echo (EchoIdentity).
            UUID authorUuid = parseUuid(authorUuidStr);
            String authorName = (authorNameStr == null || authorNameStr.isBlank()) ? "Unknown" : authorNameStr;
            if (authorUuid != null) {
                mob.setSkinTextureUrl(SourceProfileSkin.encode(authorUuid, authorName));
            }
            mob.setCustomName(Component.translatable("echo.dungeontrain.mob_name", authorName));
            mob.setCustomNameVisible(true);

            // Difficulty-scaled gear. For a curse this lands its FIGHT/FLEE self-assessment on FIGHT
            // (an armed threat); for a Love Note the same gear arms it FOR the target instead.
            if (DungeonTrainConfig.getDifficultyEnabled()
                    && DifficultyApplier.isEligible(mob, DungeonTrainConfig.getDifficultyAffectsBabyMobs())) {
                int progression = DifficultyProgression.maxTravelledCarriageIndex(level);
                DifficultyApplier.apply(mob, progression, rng, false, DifficultyApplier.StatScaling.FULL);
            }

            // Seed the kind's feeling toward the target — MIN reads as hate (FIGHT), MAX as love
            // (GREET + gifts + defends). Full NBT round-trip so the baked skin + gear above are
            // preserved (only the Feelings tag is added).
            seedFeeling(mob, targetUuid,
                    kind == NoteKind.LOVE ? FeelingLedger.MAX : FeelingLedger.MIN);

            // Carriage-contents tag so the train kill-ahead sweep spares it; + our echo markers.
            mob.addTag(CarriageContentsPlacer.contentsTagFor(carriagePIdx));
            CompoundTag persistent = mob.getPersistentData();
            persistent.putString(KEY_TARGET, targetUuid.toString());
            persistent.putString(KEY_AUTHOR, authorName);
            persistent.putString(KEY_KIND, kind.id());
            // The curse this echo is: lets the outcome of the fight be reported back to the relay
            // against the right note, so the author's story book can tell how it ended. A dev-spawned
            // echo (noteId 0) carries no id and reports nothing.
            if (noteId > 0) persistent.putInt(KEY_NOTE_ID, noteId);
            mob.setPersistenceRequired();

            if (!level.addFreshEntity(mob)) {
                LOGGER.warn("[DungeonTrain] DeathNote echo: addFreshEntity rejected at {} pIdx={}",
                    floorPos, carriagePIdx);
                return false;
            }
            // Track it so DeathNoteEchoController can steer it toward the target (frame-consistent).
            // A curse closes in to engage; a Love Note echo walks the same route but never forces a
            // target — it is coming to find them, not to fight them.
            DeathNoteEchoController.register(mob.getUUID(), targetUuid, kind);
            // Open the encounter journal — a Death Note echo is a remote echo with a grudge, so it gets
            // the same beats, the same Discord story, and (uniquely) a report back to the curse's
            // author. The audience is the cursed target explicitly, not whoever happens to be nearest.
            RemoteEchoEncounters.onDeathNoteEchoSpawned(mob, parseUuid(authorUuidStr), authorName,
                carriagePIdx, target, noteId);
            LOGGER.info("[DungeonTrain] {} echo of {} spawned at carriage {} for {}",
                kind.trophyTitle(), authorName, carriagePIdx, targetUuid);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Note echo spawn failed at {}: {}", floorPos, t.toString());
            return false;
        }
    }

    /** Seed {@code feeling} toward {@code targetUuid} without disturbing the baked skin/gear. */
    private static void seedFeeling(PlayerMobEntity mob, UUID targetUuid, float feeling) {
        try {
            CompoundTag nbt = new CompoundTag();
            mob.addAdditionalSaveData(nbt);          // capture full state (skin + gear + empty feelings)
            FeelingLedger ledger = new FeelingLedger();
            ledger.load(nbt);                        // current feelings (empty)
            ledger.set(targetUuid, feeling);
            ledger.save(nbt);                        // write feelings back under TAG_FEELINGS
            mob.readAdditionalSaveData(nbt);         // apply — skin/gear preserved, target now felt about
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Note echo: feeling seed failed: {}", t.toString());
        }
    }

    /** Parse a dash-stripped or dashed UUID string; {@code null} on failure. */
    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String s = raw.contains("-") ? raw : raw.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
