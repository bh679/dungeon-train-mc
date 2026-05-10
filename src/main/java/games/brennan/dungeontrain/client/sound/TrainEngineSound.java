package games.brennan.dungeontrain.client.sound;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

/**
 * Looping client-side ambient that follows the train. Volume is driven each
 * tick by the player's distance to the nearest loaded carriage's world-space
 * AABB:
 *
 * <ul>
 *   <li>Player inside any carriage's AABB → volume {@code 1.0} (riding it).</li>
 *   <li>Player within {@link #MAX_RANGE} blocks of an AABB → linear falloff
 *       to {@code 0} at {@code MAX_RANGE}.</li>
 *   <li>No carriages loaded, or all out of range → volume {@code 0} (sound
 *       stays alive but silent so we avoid start/stop churn).</li>
 * </ul>
 *
 * <p>Carriages are discovered through the same Sable client-API path used by
 * {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}:
 * {@link SubLevelContainer#getContainer(ClientLevel)} returns a
 * {@link ClientSubLevelContainer} whose {@link ClientSubLevelContainer#getAllSubLevels()}
 * iterates every loaded {@link ClientSubLevel}. The mod treats every Sable
 * sub-level as a carriage, so no further filter is needed.</p>
 *
 * <p>Attenuation is set to {@link Attenuation#NONE} so vanilla's distance-based
 * volume curve doesn't stack on top of ours — the in-carriage maximum-volume
 * requirement needs an inside-AABB check that vanilla can't express. The sound
 * is also marked {@code relative=true} so the engine doesn't try to spatialise
 * positional audio that we're not setting.</p>
 *
 * <p>Lifecycle is owned by {@link TrainSoundManager}: it spawns one instance
 * per {@link ClientLevel} and re-spawns after world quit/rejoin. The sound
 * stops itself if the client level becomes {@code null} (server disconnect).</p>
 */
public final class TrainEngineSound extends AbstractTickableSoundInstance {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Distance in blocks past which the engine is inaudible. */
    private static final float MAX_RANGE = 64.0f;

    /**
     * Sable's {@code SoundEngineMixin.sable$play} runs at registration time
     * and checks {@code Sable.HELPER.getContaining(level, sound.getX(), sound.getZ())}.
     * If a sub-level's XZ AABB contains the sound's world XZ, the sound is
     * wrapped in a {@code MovingSoundInstanceDelegate} which re-interprets
     * the sound's coords as model-space inside the sub-level. With
     * {@code relative=true}, that pushes our intended listener-relative
     * sound to the sub-level origin offset from the listener — broken
     * audibility for the in-carriage case. Train carriages frequently span
     * world {@code (0, 0)}, so a default {@code (0,0,0)} position would
     * always trip the wrap.
     *
     * <p>Park the source on the {@code -Z} axis at a distance no sub-level
     * AABB can plausibly reach. With {@code relative=true}, {@code (0, 0, -Z)}
     * is "directly in front of" the listener — stereo pans dead-centre. With
     * {@code Attenuation.NONE}, OpenAL's rolloff factor is set to 0 so the
     * absolute distance never attenuates gain. Net effect: full-volume
     * centered audio, exactly like a non-positional UI sound, but Sable's
     * containment check sees a point far outside any train.</p>
     */
    private static final double SABLE_BYPASS_Z = -1.0e7;

    public TrainEngineSound() {
        super(ModSounds.TRAIN_ENGINE.get(), SoundSource.AMBIENT, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        // We compute volume manually — vanilla 3D attenuation would compound
        // with our curve and clip the in-carriage maximum.
        this.attenuation = Attenuation.NONE;
        this.relative = true;
        // Tiny non-zero so SoundEngine.play() doesn't immediately skip the
        // instance (vanilla bails when volume*categoryVolume <= 0 unless
        // canStartSilent() also returns true — we belt-and-braces both).
        this.volume = 0.0001f;
        this.pitch = 1.0f;
        this.x = 0.0;
        this.y = 0.0;
        this.z = SABLE_BYPASS_Z;
        LOGGER.info("[DungeonTrain] TrainEngineSound created");
    }

    /**
     * Vanilla {@code SoundEngine.play(...)} short-circuits and never
     * registers a sound whose initial computed volume is &lt;= 0 unless
     * this returns {@code true}. Our volume is recomputed every tick from
     * player→carriage distance and starts at zero on the first tick before
     * sub-levels are loaded, so the silent start is the normal case.
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    private long tickCounter = 0;

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            this.volume = 0.0f;
            stop();
            return;
        }

        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            this.volume = 0.0f;
            return;
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        boolean insideAny = false;
        double minDistSq = Double.POSITIVE_INFINITY;
        int carriageCount = 0;

        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            double minX = box.minX();
            double minY = box.minY();
            double minZ = box.minZ();
            double maxX = box.maxX();
            double maxY = box.maxY();
            double maxZ = box.maxZ();
            // Skip the zero-AABB that fresh sub-levels report before their
            // first physics tick — same defensive check used in CarriageGroupGap.
            if (minX == 0 && minY == 0 && minZ == 0 && maxX == 0 && maxY == 0 && maxZ == 0) continue;
            carriageCount++;

            if (px >= minX && px <= maxX
                && py >= minY && py <= maxY
                && pz >= minZ && pz <= maxZ) {
                insideAny = true;
                break;
            }
            double dx = px < minX ? (minX - px) : (px > maxX ? (px - maxX) : 0.0);
            double dy = py < minY ? (minY - py) : (py > maxY ? (py - maxY) : 0.0);
            double dz = pz < minZ ? (minZ - pz) : (pz > maxZ ? (pz - maxZ) : 0.0);
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < minDistSq) minDistSq = dSq;
        }

        if (insideAny) {
            this.volume = 1.0f;
        } else if (Double.isInfinite(minDistSq)) {
            this.volume = 0.0001f;
        } else {
            float dist = (float) Math.sqrt(minDistSq);
            this.volume = Mth.clamp(1.0f - (dist / MAX_RANGE), 0.0001f, 1.0f);
        }

        // Log once per 5s (100 ticks) so we can verify the volume curve
        // without spamming the console. INFO so it shows up under default
        // forge logging — gated to only print while there's a carriage so
        // the empty-world case stays silent.
        if (++tickCounter % 100 == 0 && carriageCount > 0) {
            double dist = insideAny ? 0.0 : Math.sqrt(minDistSq);
            LOGGER.info("[DungeonTrain] TrainEngineSound: carriages={} inside={} dist={} volume={}",
                carriageCount, insideAny, String.format("%.2f", dist), String.format("%.3f", this.volume));
        }
    }
}
