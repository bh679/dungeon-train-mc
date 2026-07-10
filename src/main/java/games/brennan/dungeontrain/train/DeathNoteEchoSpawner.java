package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import games.brennan.playermob.entity.FeelingLedger;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Spawns the vengeful echo of a Death Note's author into the carriage the author died at, hostile
 * toward the cursed target only. Built like the dev echo command + {@link PlayerMobGroupSpawner}: a
 * {@code playermob:player_mob} finalized with {@code COMMAND} (so PlayerMob doesn't re-roll the
 * identity), the author's skin baked in ({@link SourceProfileSkin}), difficulty-scaled gear so its
 * FIGHT/FLEE assessment lands on FIGHT, and 0 feelings toward the target ({@link FeelingLedger#MIN}
 * → {@code Reaction.FIGHT}). Tagged as carriage contents (kill-ahead sweep spares it) and marked in
 * persistent data so {@code DeathNoteEchoController} can steer it onto the target.
 */
public final class DeathNoteEchoSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation PLAYER_MOB_ID =
        ResourceLocation.fromNamespaceAndPath("playermob", "player_mob");

    /** Persistent-data marker: the target player's UUID (string) this echo hunts. */
    public static final String KEY_TARGET = "dt_deathnote_target";
    /** Persistent-data marker: the relay note id this echo came from. */
    public static final String KEY_NOTE_ID = "dt_deathnote_id";

    private DeathNoteEchoSpawner() {}

    /**
     * Spawn the echo of {@code note}'s author at {@code floorPos} (carriage interior), hostile toward
     * {@code targetUuid}. Returns true if the mob was added. No-throw — a failure logs + returns false
     * so the caller doesn't consume the note on a bad spawn.
     */
    public static boolean spawn(ServerLevel level, BlockPos floorPos, int carriagePIdx,
                                DeathNotePool.Note note, UUID targetUuid) {
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
            UUID authorUuid = parseUuid(note.authorUuid());
            String authorName = note.authorName() == null || note.authorName().isBlank()
                ? "Unknown" : note.authorName();
            if (authorUuid != null) {
                mob.setSkinTextureUrl(SourceProfileSkin.encode(authorUuid, authorName));
            }
            mob.setCustomName(Component.literal("Echo of " + authorName));
            mob.setCustomNameVisible(true);

            // Difficulty-scaled gear so its FIGHT/FLEE self-assessment lands on FIGHT (an armed threat).
            if (DungeonTrainConfig.getDifficultyEnabled()
                    && DifficultyApplier.isEligible(mob, DungeonTrainConfig.getDifficultyAffectsBabyMobs())) {
                int progression = DifficultyProgression.maxTravelledCarriageIndex(level);
                DifficultyApplier.apply(mob, progression, rng, false, true);
            }

            // Seed 0 feelings toward the target → reactionToward == FIGHT. Full NBT round-trip so the
            // baked skin + gear above are preserved (only the Feelings tag is added).
            seedHostileFeeling(mob, targetUuid);

            // Carriage-contents tag so the train kill-ahead sweep spares it; + our echo markers.
            mob.addTag(CarriageContentsPlacer.contentsTagFor(carriagePIdx));
            CompoundTag persistent = mob.getPersistentData();
            persistent.putString(KEY_TARGET, targetUuid.toString());
            persistent.putInt(KEY_NOTE_ID, note.id());
            mob.setPersistenceRequired();

            if (!level.addFreshEntity(mob)) {
                LOGGER.warn("[DungeonTrain] DeathNote echo: addFreshEntity rejected at {} pIdx={}",
                    floorPos, carriagePIdx);
                return false;
            }
            LOGGER.info("[DungeonTrain] DeathNote echo of {} spawned at carriage {} hunting {}",
                authorName, carriagePIdx, targetUuid);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] DeathNote echo spawn failed at {}: {}", floorPos, t.toString());
            return false;
        }
    }

    /** Seed feeling MIN (0 → hostile) toward {@code targetUuid} without disturbing the baked skin/gear. */
    private static void seedHostileFeeling(PlayerMobEntity mob, UUID targetUuid) {
        try {
            CompoundTag nbt = new CompoundTag();
            mob.addAdditionalSaveData(nbt);          // capture full state (skin + gear + empty feelings)
            FeelingLedger ledger = new FeelingLedger();
            ledger.load(nbt);                        // current feelings (empty)
            ledger.set(targetUuid, FeelingLedger.MIN);
            ledger.save(nbt);                        // write feelings back under TAG_FEELINGS
            mob.readAdditionalSaveData(nbt);         // apply — skin/gear preserved, target now hated
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote echo: feeling seed failed: {}", t.toString());
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
