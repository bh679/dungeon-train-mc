package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Surviving the cheat system, without punching a hole in it.</b> The command that earns
 * this is op-only, so two separate parts of the cheat system would otherwise erase the reward.
 * {@link CommandAllowlist} exempts the exact form {@code /advancement revoke @s everything}
 * (and only that form — any other target is still cheating), which stops the Free Play
 * confirmation from cancelling the command. That alone isn't enough: {@code OperatorIntegrity}
 * treats cheats being <em>available</em> as Free Play, so anyone who <em>can</em> run the command
 * is a cheated run before they run it, and
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity#persistsAdvancement} would drop the award
 * on its way to the cross-world profile.
 *
 * <p>And a third leg, {@link games.brennan.dungeontrain.mixin.CommandsSelfRevokeMixin}: vanilla
 * won't even parse {@code /advancement …} for a player without cheats, so a clean survival run
 * could never run the command in the first place. That mixin routes exactly this one command,
 * for a capstone-holder only, into {@link #performSelfWipe}. The two exemptions above still earn
 * their keep — they cover the operator, who goes down the ordinary vanilla path instead.
 *
 * <p>So {@link #checkArmed} writes to {@link GlobalAchievementStore} itself, at the one call site
 * that has verified the player held the capstone and actually wiped it. Deliberately <em>not</em>
 * an id-level exemption in {@code persistsAdvancement}: vanilla
 * {@code /advancement grant @s everything} awards {@code impossible} criteria directly, so an
 * exemption by id would let a plain grant launder this advancement into the profile. As it
 * stands a grant still lights the toast in that session — unavoidable for any code-granted
 * advancement, and true of the capstone already — but it can never bank.</p>
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
     * Is this the command that earns it — {@code /advancement revoke @s everything}, exactly?
     *
     * <p>Delegates to {@link CommandAllowlist#isSelfRevokeEverything}, which is also what decides
     * the command doesn't taint the run. One classifier, two call sites: the command that is
     * forgiven and the command that is rewarded can never drift apart. Self-target only — you may
     * wipe your own slate, never someone else's.</p>
     */
    public static boolean isSelfRevokeEverything(String rawCommand) {
        return CommandAllowlist.isSelfRevokeEverything(rawCommand);
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
            // Bank it here rather than leaving it to the earn-event's persistence gate. The gate
            // would drop it: the command that earns this needs permission level 2, and
            // OperatorIntegrity treats cheats being AVAILABLE as Free Play, so the run is always
            // cheated by the time we get here — the reward would toast and then be forgotten.
            // Writing at this one call site (rather than exempting the id in
            // RunIntegrity.persistsAdvancement) is what keeps it honest: this is the only path
            // that checks the player actually held the capstone and actually wiped it, so
            // /advancement grant @s everything still awards the advancement live but can never
            // launder it into the cross-world profile.
            GlobalAchievementStore.append(player.getUUID(), ID);
            LOGGER.info("[DungeonTrain] Granted start-again advancement (It's Not That Simple) to {}",
                player.getName().getString());
        }
    }

    /**
     * Run the wipe ourselves, for a player who holds the capstone but has no cheats — the path
     * {@link games.brennan.dungeontrain.mixin.CommandsSelfRevokeMixin} routes
     * {@code /advancement revoke @s everything} down when vanilla would refuse to parse it at all
     * (the {@code advancement} node requires permission 2, so it isn't in an ordinary player's
     * command tree).
     *
     * <p>Mirrors vanilla {@code AdvancementCommands.Action.REVOKE} + {@code perform(...)} so the
     * player gets the real command's behaviour and its own chat feedback, not an imitation: every
     * advancement with progress has its completed criteria revoked, and the many-to-one success
     * line is sent with the same translation key vanilla uses.</p>
     *
     * <p>{@link #checkArmed} is called inline rather than left to the player tick: the wipe has
     * already finished on this thread, which is exactly the condition it verifies. The tick path
     * stays for the operator case, where vanilla executes the revoke after {@code CommandEvent}
     * has armed.</p>
     */
    public static void performSelfWipe(ServerPlayer player, CommandSourceStack source) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        armIfEligible(player);

        int revoked = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            if (!progress.hasProgress()) continue;
            // Copy first: revoking mutates what getCompletedCriteria() reflects. (It is an
            // Iterable, not a Collection, so this is a manual drain rather than List.copyOf.)
            List<String> completed = new ArrayList<>();
            progress.getCompletedCriteria().forEach(completed::add);
            for (String criterion : completed) {
                player.getAdvancements().revoke(holder, criterion);
            }
            revoked++;
        }

        int total = revoked;
        source.sendSuccess(() -> Component.translatable(
            "commands.advancement.revoke.many.to.one.success", total, player.getDisplayName()), true);
        LOGGER.info("[DungeonTrain] Self-wipe without cheats: revoked {} advancement(s) for {}",
            total, player.getName().getString());

        checkArmed(player);
    }

    /** Drop any pending arm for a departing player, so a disconnect mid-command can't leak. */
    public static void disarm(UUID uuid) {
        ARMED.remove(uuid);
    }
}
