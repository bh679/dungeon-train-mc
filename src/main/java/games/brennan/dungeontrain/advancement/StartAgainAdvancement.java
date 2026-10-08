package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirementOverrides;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
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
 * <p><b>Reaching the command without cheats.</b> Vanilla gates the whole {@code advancement} node
 * behind permission 2 — for an ordinary player it isn't even in the command tree, so it neither
 * autocompletes nor parses, and {@code @s} is refused as a selector besides. Three pieces open
 * exactly this one form to a {@linkplain #holdsBankedCapstone capstone-holder}, and only while they
 * hold it: {@link SelfRevokeCommandAccess} rewrites the vanilla nodes' requirements so
 * {@code advancement → revoke → <targets> → everything} is sent to (and parses for) the holder,
 * {@link SelfSelectorGrant} admits a bare {@code @s}, and the {@code CommandEvent} guard in
 * {@code AchievementEvents} cancels any other {@code /advancement …} form from a non-op before the
 * cheat detector can see it. The command tree is re-sent whenever the answer changes — capstone
 * granted at login or live, or wiped here — so the autocomplete appears and disappears with the
 * eligibility. {@link CommandAllowlist} exempts the exact form so the Free Play confirmation never
 * holds it; that exemption is what keeps the run clean through the wipe.
 *
 * <p><b>Two honesty gates, and why they are here rather than in {@code persistsAdvancement}.</b>
 * {@link #checkArmed} writes to {@link GlobalAchievementStore} itself, at the one call site that
 * has verified the player held the capstone and actually wiped it. Deliberately <em>not</em> an
 * id-level exemption in {@link RunIntegrity#persistsAdvancement}: vanilla
 * {@code /advancement grant @s everything} awards {@code impossible} criteria directly, so an
 * exemption by id would let a plain grant launder this advancement into the profile. As it
 * stands a grant still lights the toast in that session — unavoidable for any code-granted
 * advancement, and true of the capstone already — but it can never bank.
 *
 * <p>That local write is gated twice, because writing at a verified call site is only as honest as
 * what it verifies. {@link #shouldArm} requires the capstone to be <em>banked</em>, not merely
 * present in the live tree — a granted burrito is not an earned one — and {@link #shouldBank}
 * refuses the write outright from a Free Play run ({@link RunIntegrity#isCheated}), the same answer
 * every other advancement gets from {@code persistsAdvancement}. An earlier version skipped the
 * second gate on the reasoning that the command is op-only and therefore the run is always cheated
 * by the time we get here; the mixin path above made that false, and a Free Play player who granted
 * themselves the capstone could bank this while the burrito itself was correctly refused.</p>
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
     * Remember that {@code player} is about to run a revoke-everything, but only when
     * {@link #shouldArm} says they've earned the right — no burrito, nothing armed, nothing ever
     * granted. Called from the {@code CommandEvent} hook, before execution. One sidecar read per
     * revoke command, which is as rare as commands get.
     *
     * @return {@code true} when the player was armed — the caller then runs {@link #wipe} in place
     *         of vanilla's revoke-everything
     */
    public static boolean armIfEligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder capstone = mgr.get(CompletionistAdvancement.ID);
        AdvancementHolder self = mgr.get(ID);
        if (capstone == null || self == null) return false; // data not loaded (e.g. datapack stripped)
        Set<ResourceLocation> banked = GlobalAchievementStore.read(player.getUUID());
        if (!shouldArm(banked.contains(ID),
                       player.getAdvancements().getOrStartProgress(capstone).isDone(),
                       banked.contains(CompletionistAdvancement.ID))) {
            return false;
        }
        ARMED.add(player.getUUID());
        return true;
    }

    /**
     * The wipe that earns this, in place of vanilla's revoke-everything: clears only what the capstone
     * is made of — its {@linkplain CompletionistAdvancement#isRequired required set}, the capstone
     * itself and this advancement — and leaves every other advancement earned (The Secrete Menu, the
     * editor tree, relay-{@code notRequired} ones, vanilla and other mods'). Starting again
     * means starting the burrito again, not losing what never counted towards it. Revoking rather than
     * re-awarding afterwards also means no kept advancement re-runs its rewards.
     *
     * @return how many advancements lost at least one criterion
     */
    public static int wipe(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return 0;
        Set<ResourceLocation> notRequired = AdvancementRequirementOverrides.notRequired();
        List<AdvancementHolder> targets = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (isWiped(holder.id(), CompletionistAdvancement.isRequired(server.getAdvancements(), holder, notRequired))) targets.add(holder);
        }
        int cleared = 0;
        for (AdvancementHolder holder : targets) {
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            boolean any = false;
            for (String criterion : toList(progress.getCompletedCriteria())) {   // copy: revoke mutates it
                if (player.getAdvancements().revoke(holder, criterion)) any = true;
            }
            if (any) cleared++;
        }
        LOGGER.info("[DungeonTrain] Start-again wipe for {}: cleared {} capstone advancement(s), kept the rest",
            player.getName().getString(), cleared);
        return cleared;
    }

    private static List<String> toList(Iterable<String> criteria) {
        List<String> out = new ArrayList<>();
        for (String c : criteria) out.add(c);
        return out;
    }

    /**
     * Does the start-again wipe clear advancement {@code id}? By default only the capstone's parts:
     * its required set, the capstone, and this advancement. An advancement's own setting in
     * {@code advancement_tabs.json} ({@code startAgainReset}, written by the advancement editor) wins
     * over that, except for the capstone pair, which is always cleared. Package-private for unit tests.
     */
    static boolean isWiped(ResourceLocation id, boolean requiredByCapstone) {
        if (CompletionistAdvancement.ID.equals(id) || ID.equals(id)) return true;
        if (TabGateways.isLinked(id)) return false;
        Boolean override = TabGateways.layout().startAgainReset().get(id.toString());
        return override != null ? override : requiredByCapstone;
    }

    /**
     * The arming rule as a pure predicate — the part that actually encodes "earned the burrito,
     * honestly, and hasn't banked this yet". Package-private for unit tests; the live-player
     * plumbing above is a thin wrapper, as with {@link FarStartAdvancement#shouldGrant}.
     *
     * <p>The capstone is checked twice on purpose. It must be <b>live</b>, because the reward is
     * for wiping a tree that really holds it; and it must be <b>banked</b>, because only a clean
     * run writes to the cross-world profile, so a burrito conjured by
     * {@code /advancement grant @s only …/completionist} in a Free Play world is exactly the case
     * that must not open this door.</p>
     *
     * <p>The "already got it" term is likewise the banked copy, not the live one. An unbanked live
     * copy is what an earlier {@code /advancement grant} of this very advancement leaves behind;
     * treating that as earned would lock the player out of ever earning it honestly.</p>
     */
    static boolean shouldArm(boolean selfBanked, boolean capstoneLiveDone, boolean capstoneBanked) {
        return !selfBanked && capstoneLiveDone && capstoneBanked;
    }

    /**
     * Does a confirmed wipe bank the reward? Only out of a clean run. A cheated run still awards it
     * live — it toasts, exactly like every other advancement earned in Free Play — but the
     * cross-world profile stays honest. Package-private for unit tests.
     */
    static boolean shouldBank(boolean cheated) {
        return !cheated;
    }

    /**
     * Does {@code player} hold the capstone both in this world's tree and in their cross-world
     * profile? The gate {@link games.brennan.dungeontrain.mixin.CommandsSelfRevokeMixin} opens the
     * command on, kept here beside {@link #shouldArm} so the command that is permitted and the
     * command that is rewarded can never drift apart.
     */
    public static boolean holdsBankedCapstone(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        AdvancementHolder capstone = server.getAdvancements().get(CompletionistAdvancement.ID);
        if (capstone == null) return false; // capstone data not loaded (e.g. datapack stripped)
        return player.getAdvancements().getOrStartProgress(capstone).isDone()
            && GlobalAchievementStore.read(player.getUUID()).contains(CompletionistAdvancement.ID);
    }

    /**
     * Grant the reward on the tick after an armed revoke, once the capstone is
     * confirmed cleared — granting afterwards is the whole point, so the award
     * isn't swept up by the wipe that earned it. Disarms either way: a revoke
     * that left the capstone standing (wrong target, failed command) simply
     * drops the arm. Cheap: early-returns on the common empty-set case.
     *
     * <p>The live award is unconditional once the wipe is confirmed; only the write to the
     * cross-world profile answers to {@link #shouldBank}.</p>
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
        refreshCommandTree(player); // capstone gone → /advancement leaves the tree again
        // No "already earned" check: the wipe that earned this just cleared the live copy, and an
        // unbanked one left over from a /advancement grant is precisely what shouldArm ignores.

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (!granted) return;
        // Bank it here rather than leaving it to the earn-event's persistence gate, which cannot
        // tell this apart from a laundered /advancement grant — but apply that gate's own answer,
        // so a Free Play run gets the toast and nothing more. See the class javadoc.
        if (!shouldBank(RunIntegrity.isCheated(player))) {
            LOGGER.info("[DungeonTrain] Granted start-again advancement (It's Not That Simple) to {} "
                + "— live only, NOT banked: Free Play run", player.getName().getString());
            return;
        }
        GlobalAchievementStore.append(player.getUUID(), ID);
        LOGGER.info("[DungeonTrain] Granted start-again advancement (It's Not That Simple) to {} (banked)",
            player.getName().getString());
    }

    /**
     * Re-send the player's command tree so {@code /advancement} appears or disappears with their
     * eligibility ({@link SelfRevokeCommandAccess}). Vanilla only re-sends on permission changes;
     * the capstone being granted or wiped is our equivalent.
     */
    public static void refreshCommandTree(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) server.getCommands().sendCommands(player);
    }

    /** Drop any pending arm for a departing player, so a disconnect mid-command can't leak. */
    public static void disarm(UUID uuid) {
        ARMED.remove(uuid);
    }
}
