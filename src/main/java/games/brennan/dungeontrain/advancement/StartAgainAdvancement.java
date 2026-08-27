package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "It's Not That Simple" — the one advancement that sits <em>after</em>
 * {@link CompletionistAdvancement} ("Everything Burrito"). Earned by wiping the
 * slate: a player who already holds the capstone runs
 * {@code /advancement revoke @s everything}, and this is granted once the wipe
 * has actually gone through — so it survives the very command that earns it.
 *
 * <p><b>Not a capstone prerequisite.</b> The burrito's required set is computed
 * dynamically from the live advancement registry, so this id is excluded there
 * explicitly (see {@link CompletionistAdvancement#checkAndGrant}); requiring it
 * would make the burrito unreachable, since you cannot wipe a capstone you have
 * not earned.</p>
 *
 * <p><b>Arm-then-check.</b> NeoForge's {@code CommandEvent} fires <em>before</em>
 * the command executes, which is far too early to grant — the award would be
 * cleared by the revoke that follows it. So the command hook only
 * {@linkplain #armIfEligible arms} the player (and only if they hold the capstone
 * right now), and {@link #checkArmed}, driven from the player tick, does the
 * grant on the following tick once the capstone is confirmed gone. A revoke that
 * did not actually clear the player's tree (wrong target, failed command)
 * disarms without granting.</p>
 *
 * <p>The direct award mirrors {@link CompletionistAdvancement} and passes through
 * the same cross-world persistence gate in {@code AchievementEvents}
 * ({@link games.brennan.dungeontrain.cheat.RunIntegrity#persistsAdvancement}).
 * {@code /advancement revoke} is deliberately exempt from the cheat allowlist —
 * see {@link games.brennan.dungeontrain.cheat.CommandAllowlist} — because
 * revoking only ever destroys the player's own progress; without that exemption
 * the run would go Free Play and this reward could never bank.</p>
 */
public final class StartAgainAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id; referenced by the wiring in {@code AchievementEvents} and by the capstone's exclusion. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/start_again");

    /** Players whose in-flight command is a capstone-clearing revoke; drained on the next tick. */
    private static final Set<UUID> ARMED = ConcurrentHashMap.newKeySet();

    private StartAgainAdvancement() {}

    /**
     * Core, string-based classifier for {@code /advancement revoke <targets> everything}:
     * Brigadier-free so it is unit-testable, and namespace-tolerant
     * ({@code /minecraft:advancement …}) for the same reason
     * {@link games.brennan.dungeontrain.cheat.CommandAllowlist} works off the raw string.
     *
     * @return true for a revoke-everything spelling, false for {@code grant}/{@code set},
     *         for {@code revoke … only <id>} / {@code … from <id>} / {@code … through <id>},
     *         and for anything that isn't the advancement command
     */
    public static boolean isRevokeEverything(String rawCommand) {
        String cmd = rawCommand == null ? "" : rawCommand.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
        if (cmd.isEmpty()) return false;
        String[] parts = cmd.split("\\s+");
        if (parts.length < 3) return false; // "advancement revoke <targets> everything" is 4 tokens
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        if (!root.equals("advancement")) return false;
        if (!parts[1].toLowerCase(Locale.ROOT).equals("revoke")) return false;
        return parts[parts.length - 1].toLowerCase(Locale.ROOT).equals("everything");
    }

    /**
     * Remember that {@code player} is about to run a revoke-everything, but only
     * when they currently hold the capstone — no burrito, nothing armed, nothing
     * ever granted. Called from the {@code CommandEvent} hook, before execution.
     */
    public static void armIfEligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder capstone = mgr.get(CompletionistAdvancement.ID);
        AdvancementHolder self = mgr.get(ID);
        if (capstone == null || self == null) return; // data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return;      // already earned
        if (!player.getAdvancements().getOrStartProgress(capstone).isDone()) return; // no burrito to clear
        ARMED.add(player.getUUID());
    }

    /**
     * Grant the reward on the tick after an armed revoke, once the capstone is
     * confirmed cleared — granting afterwards is the whole point, so the award
     * isn't swept up by the wipe that earned it. Disarms either way: a revoke
     * that left the capstone standing (wrong target, failed command) simply
     * drops the arm. Cheap: early-returns on the common empty-set case.
     */
    public static void checkArmed(ServerPlayer player) {
        if (ARMED.isEmpty()) return;
        if (!ARMED.remove(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder capstone = mgr.get(CompletionistAdvancement.ID);
        AdvancementHolder self = mgr.get(ID);
        if (capstone == null || self == null) return;
        if (player.getAdvancements().getOrStartProgress(capstone).isDone()) return; // wipe didn't happen
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return;     // already earned

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted start-again advancement (It's Not That Simple) to {}",
                player.getName().getString());
        }
    }

    /** Drop any pending arm for a departing player, so a disconnect mid-command can't leak. */
    public static void disarm(UUID uuid) {
        ARMED.remove(uuid);
    }
}
