package games.brennan.dungeontrain.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bridge into <b>Sable: Ragdolls</b> ({@code sable_player_ragdoll}), the third-party mod whose
 * physics ragdolls stand in for a dead player's body ({@code ship.sable.DeathBodyRagdoll}).
 *
 * <p>The mod is a required dependency, but DT never trusts a third-party API to keep linking: every
 * call is confined to the nested {@link Api} holder, which is only loaded once the mod list says the
 * mod is there, and the first {@link LinkageError} switches the bridge off for the rest of the
 * session. Without it the death scene plays exactly as it did before the body existed.</p>
 */
public final class RagdollBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mod id Sable: Ragdolls declares in its {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "sable_player_ragdoll";

    private static volatile boolean present = detectPresent();

    private RagdollBridge() {}

    /** Present when the API class loads AND the mod list has the mod (classpath alone outside a mod environment). */
    private static boolean detectPresent() {
        try {
            Class.forName("dev.leo.sableplayerragdoll.api.RagdollAPI", false, RagdollBridge.class.getClassLoader());
        } catch (Throwable t) {
            return false;
        }
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Is Sable: Ragdolls installed and linking? */
    public static boolean isPresent() {
        return present;
    }

    /**
     * Spawn a playerless ragdoll wearing {@code profile}'s skin and {@code equipmentFrom}'s gear,
     * which nobody can grab and which the mod itself expires after {@code lifetimeTicks} if DT has
     * not released it first. Returns the root sub-level id, or null when the mod is absent, the
     * spawn was refused, or the API failed.
     *
     * @param velocityPerSecond initial linear velocity in blocks per second
     */
    @Nullable
    public static UUID spawnBody(ServerLevel level, Vec3 position, float yawDegrees, GameProfile profile,
                                 Vec3 velocityPerSecond, ServerPlayer equipmentFrom, int lifetimeTicks) {
        if (!present) return null;
        try {
            return Api.spawn(level, position, yawDegrees, profile, velocityPerSecond, equipmentFrom, lifetimeTicks);
        } catch (LinkageError e) {
            disable(e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Sable: Ragdolls refused the death body: {}", e.toString());
            return null;
        }
    }

    /** Release a body spawned by {@link #spawnBody}. Safe when it is already gone or unknown. */
    public static void release(UUID bodyId) {
        if (!present || bodyId == null) return;
        try {
            Api.release(bodyId);
        } catch (LinkageError e) {
            disable(e);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Sable: Ragdolls failed to release body {}: {}", bodyId, e.toString());
        }
    }

    /** Forget every session handle (server stopped). */
    public static void clear() {
        if (!present) return;
        try {
            Api.clear();
        } catch (LinkageError e) {
            disable(e);
        }
    }

    private static void disable(LinkageError e) {
        present = false;
        LOGGER.warn("[DungeonTrain] Sable: Ragdolls API no longer links; death bodies are off for this session: {}", e.toString());
    }

    /** Hard imports live here only, so the outer class loads without the mod. */
    private static final class Api {

        /** Live sessions by root sub-level id; the mod exposes release only through the session handle. */
        private static final Map<UUID, PlayerlessRagdollSession> SESSIONS = new HashMap<>();

        private Api() {}

        @Nullable
        static UUID spawn(ServerLevel level, Vec3 position, float yawDegrees, GameProfile profile,
                          Vec3 velocityPerSecond, ServerPlayer equipmentFrom, int lifetimeTicks) {
            PlayerlessRagdollSession session = RagdollAPI.spawnPlayerless(level, position, yawDegrees, profile,
                velocityPerSecond, PlayerlessDespawnRule.afterTicks(lifetimeTicks));
            if (session == null) return null;
            UUID id = session.id();
            SESSIONS.put(id, session);
            RagdollAPI.copyEquipmentFrom(level, id, equipmentFrom);
            RagdollAPI.setGrabDisabled(level, id, true);
            return id;
        }

        static void release(UUID id) {
            PlayerlessRagdollSession session = SESSIONS.remove(id);
            if (session != null) session.release();
        }

        static void clear() {
            SESSIONS.clear();
        }
    }
}
