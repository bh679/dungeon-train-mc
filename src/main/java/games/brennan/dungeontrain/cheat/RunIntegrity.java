package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.FreePlayReport;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import org.slf4j.Logger;

/**
 * "Run integrity" — the cheat-taint state of a player's current world/run.
 *
 * <p>A run is "cheated" the moment the player switches to
 * creative/spectator/cinematographer, or uses a non-allowlisted command (see
 * {@link games.brennan.dungeontrain.event.CheatDetectionEvents} and
 * {@link CommandAllowlist}). The taint is a sticky per-world attachment
 * ({@link ModDataAttachments#RUN_CHEATED}): once set it survives relog and
 * respawn; a brand-new world / run starts clean.</p>
 *
 * <p><b>One exception, and only one.</b> The Train Editor forces the player into
 * creative to edit a plot, and restores their game mode and inventory when they
 * leave — DT put them there and nothing came out of it. A run tainted by nothing
 * but that carries {@link ModDataAttachments#RUN_CHEATED_EDITOR_ONLY} as well
 * ({@link #markEditorCheated}), and turning the custom content off hands the run
 * back ({@link #clearEditorOnlyTaint}). Any other cause revokes the exemption for
 * good, including one committed after the editor session.</p>
 *
 * <p>The taint also arrives session-wide, without touching the player, from
 * {@link AisDataIntegrity} (modified AIS config), {@link CheatModIntegrity}
 * (known cheat mods) and {@link EditorContentIntegrity} (custom Train Editor
 * content). Those clear themselves when the cause goes away.</p>
 *
 * <p>While cheated, advancements still earn live (the advancement screen works
 * in any mode), but they are <b>not</b> written to the cross-world
 * {@code GlobalAchievementStore} profile, and the global lifetime stats in
 * {@code GlobalPlayerStats} / {@code GlobalNarrativeProgress} stop accruing.
 * Editor-authoring advancements ({@code editor/*}) are mode-agnostic and keep
 * persisting — authoring legitimately happens in creative.</p>
 */
public final class RunIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RunIntegrity() {}

    /**
     * Is this player's current run Free Play? True when the run is permanently
     * cheated ({@link #isPermanentlyCheated}), OR when the whole server session
     * is Free Play because AIS data was changed
     * ({@link AisDataIntegrity#isSessionFreePlay}), DT's own balance config was
     * changed ({@link DtConfigIntegrity#isSessionFreePlay}), a known cheat mod is
     * installed ({@link CheatModIntegrity#isSessionFreePlay}), or custom Train
     * Editor content is active ({@link EditorContentIntegrity#isSessionFreePlay}),
     * OR the world's portal rate has been retuned
     * ({@link PortalTuningIntegrity#isWorldFreePlay} — per-world and permanent
     * rather than per-session and derived, see that class).
     * Every persistence gate keys off this, so the session taints inherit all
     * Free Play behaviour.
     */
    public static boolean isCheated(ServerPlayer player) {
        return AisDataIntegrity.isSessionFreePlay()
            || DtConfigIntegrity.isSessionFreePlay()
            || CheatModIntegrity.isSessionFreePlay()
            || EditorContentIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay()
            || isPermanentlyCheated(player);
    }

    /**
     * Has this player's current world/run been permanently cheated (the sticky
     * {@code RUN_CHEATED} attachment)? Unlike {@link #isCheated} this ignores the
     * session-only AIS taint — use it where the <em>permanent</em> state matters,
     * e.g. deciding whether a tainting action still needs recording.
     */
    public static boolean isPermanentlyCheated(ServerPlayer player) {
        return Boolean.TRUE.equals(player.getData(ModDataAttachments.RUN_CHEATED.get()));
    }

    /**
     * Switch the run to Free Play: set the flag, apply the {@code Free Play}
     * status effect, and send one gentle, non-judgemental chat line. Idempotent —
     * only the first {@code false → true} transition acts, so the action that
     * tripped it (a confirmed mode switch / command, the game-mode backstop, or a
     * login already in creative) won't double-notify.
     *
     * @param cause a soft localized phrase naming what started Free Play (e.g.
     *              "You switched to Creative.") — shown after the title.
     */
    /**
     * Is the run already Free Play for some reason <em>other than</em> the custom Train Editor
     * content itself?
     *
     * <p>Exists because {@link #isVisiblySessionFreePlay} can't answer this one:
     * {@link EditorContentIntegrity#isSessionFreePlay} is true whenever custom content is loading,
     * so asking it at the custom-content prompt always says "already Free Play" and the prompt
     * would never appear. This is {@link #isCheated} with that one term removed.</p>
     *
     * <p>The caller that matters is the join-time custom-content prompt: its whole question is
     * "keep your designs and run as Free Play, or drop them and keep your stats". When the run is
     * Free Play anyway — creative mode, a cheat mod, a retuned config — there is nothing left to
     * trade, so asking is just a modal in the way.</p>
     */
    public static boolean isFreePlayApartFromCustomContent(ServerPlayer player) {
        return AisDataIntegrity.isSessionFreePlay()
            || DtConfigIntegrity.isSessionFreePlay()
            || CheatModIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay()
            || isPermanentlyCheated(player);
    }

    /**
     * Is the session <em>already visibly</em> Free Play — i.e. the player has been told so and has
     * the effect — for a reason that has nothing to do with them? Callers use this to skip a
     * confirmation prompt that would have nothing to confirm, and to record the permanent taint
     * quietly instead of notifying twice.
     *
     * <p>Covers the AIS-config, DT-config, custom-editor-content and retuned-portal-rate taints. Deliberately
     * <b>not</b> {@link CheatModIntegrity} — that source predates this helper and still takes the
     * prompt / notify path; folding it in would change its Discord reporting, which is a separate
     * call.</p>
     */
    public static boolean isVisiblySessionFreePlay() {
        return AisDataIntegrity.isSessionFreePlay()
            || DtConfigIntegrity.isSessionFreePlay()
            || EditorContentIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay();
    }

    public static void markCheated(ServerPlayer player, Component cause) {
        // Whatever this cause is, it is not the editor's own forced game-mode switch, so the run
        // loses the editor-only exemption — and it must lose it BEFORE the early return below,
        // or a real cheat committed during an editor-tainted run would leave the exemption
        // standing and hand the player a clean run they didn't earn.
        revokeEditorOnlyExemption(player);
        // Idempotence keys off the permanent attachment, NOT isCheated(): during
        // a session-only config taint a tainting action must still be recorded
        // permanently, or restoring the config would forget it.
        if (isPermanentlyCheated(player)) return;
        player.setData(ModDataAttachments.RUN_CHEATED.get(), Boolean.TRUE);
        applyFreePlayEffect(player);
        LOGGER.info("[DungeonTrain] Run is now Free Play for {} — {}",
            player.getName().getString(), cause.getString());
        if (isVisiblySessionFreePlay()) {
            // Already visibly in Free Play this session (the AIS, DT-config or custom-content
            // notice on join) — record the permanent taint quietly, no second chat line /
            // Discord post.
            return;
        }
        sendFreePlayNotice(player, cause);
        // Mirror the transition to Discord (best-effort; never disrupts the run state above).
        FreePlayReport.post(player, cause);
    }

    // ---- The editor-only taint ----

    /**
     * Taint the run for an <b>authoring session</b> — the Train Editor forcing the player into
     * creative to edit a plot ({@code CarriageEditor.rememberReturn} and the tunnel / portal-room
     * equivalents), or one of the editor-authoring commands that leads straight into one.
     *
     * <p>Identical to {@link #markCheated} in every visible way — same flag, same effect, same
     * notice — but it also records that <em>this</em> is why, so turning the custom content off
     * later can hand the run back ({@link #clearEditorOnlyTaint}). It is only safe to hand back
     * because the editor restores the player's inventory and game mode on exit: DT put them in
     * creative, and nothing came out of it.</p>
     *
     * <p>Never softens an existing taint: a run already cheated for some other reason stays that
     * way, so entering the editor from a run that was already dirty changes nothing.</p>
     */
    public static void markEditorCheated(ServerPlayer player, Component cause) {
        if (isPermanentlyCheated(player)) return; // an existing taint is never downgraded to "just editing"
        player.setData(ModDataAttachments.RUN_CHEATED_EDITOR_ONLY.get(), Boolean.TRUE);
        player.setData(ModDataAttachments.RUN_CHEATED.get(), Boolean.TRUE);
        applyFreePlayEffect(player);
        LOGGER.info("[DungeonTrain] Run is now Free Play for {} (editor authoring — reversible) — {}",
            player.getName().getString(), cause.getString());
        if (isVisiblySessionFreePlay()) return; // already told them this session
        sendFreePlayNotice(player, cause);
        FreePlayReport.post(player, cause);
    }

    /**
     * Is this run cheated <em>only</em> because of a Train Editor authoring session, and therefore
     * still recoverable? Both halves are required: the exemption flag means nothing without the
     * taint it qualifies.
     */
    public static boolean isEditorOnlyCheated(ServerPlayer player) {
        return isPermanentlyCheated(player)
            && Boolean.TRUE.equals(player.getData(ModDataAttachments.RUN_CHEATED_EDITOR_ONLY.get()));
    }

    /**
     * Permanently disqualify this run from {@link #clearEditorOnlyTaint}. Called for every taint
     * cause that isn't the editor's own switch, and when an editor session ends without its
     * restoring exit (a logout inside the editor) — an un-restored session can't be vouched for.
     */
    public static void revokeEditorOnlyExemption(ServerPlayer player) {
        if (!Boolean.TRUE.equals(player.getData(ModDataAttachments.RUN_CHEATED_EDITOR_ONLY.get()))) return;
        player.setData(ModDataAttachments.RUN_CHEATED_EDITOR_ONLY.get(), Boolean.FALSE);
        LOGGER.info("[DungeonTrain] {} no longer qualifies for the editor-only Free Play exemption.",
            player.getName().getString());
    }

    /**
     * Give the run back, when the only thing that ever took it was an editor session. Clears the
     * permanent taint and reconciles the badge; a no-op (returning false) for every other run, so
     * callers can offer this unconditionally and let it decide.
     */
    public static boolean clearEditorOnlyTaint(ServerPlayer player) {
        if (!isEditorOnlyCheated(player)) return false;
        player.setData(ModDataAttachments.RUN_CHEATED.get(), Boolean.FALSE);
        player.setData(ModDataAttachments.RUN_CHEATED_EDITOR_ONLY.get(), Boolean.FALSE);
        LOGGER.info("[DungeonTrain] Run is no longer Free Play for {} — the only cause was a Train "
            + "Editor session and its content is now off.", player.getName().getString());
        reconcileFreePlayEffect(player);
        return true;
    }

    /**
     * The standard Free Play chat notice: bold title, grey cause, grey
     * consequence line. Shared by {@link #markCheated} and the session-only AIS
     * taint's login notice ({@code CheatDetectionEvents.onLogin}).
     */
    public static void sendFreePlayNotice(ServerPlayer player, Component cause) {
        MutableComponent msg = Component.translatable("chat.dungeontrain.free_play.title")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(CommonComponents.SPACE)
            .append(cause.copy().withStyle(ChatFormatting.GRAY))
            .append(CommonComponents.NEW_LINE)
            .append(Component.translatable("chat.dungeontrain.free_play.consequence")
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(msg);
    }

    /**
     * Apply the permanent, run-scoped {@code Free Play} marker effect — infinite
     * duration, no particles, HUD icon shown. Re-applied on login and respawn
     * while the run is Free Play, since effects are cleared on death and can be
     * removed by milk. ({@code -1} = infinite duration in 1.21.)
     */
    public static void applyFreePlayEffect(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(
            ModMobEffects.FREE_PLAY, -1, 0,
            /* ambient */ true, /* visible particles */ false, /* showIcon */ true));
    }

    /**
     * Take the {@code Free Play} marker off. Only ever correct once the run has genuinely stopped
     * being Free Play — {@code CheatDetectionEvents.onEffectRemove} still cancels any removal
     * while {@link #isCheated}, so this cannot be used to shed the badge on a cheated run.
     */
    public static void clearFreePlayEffect(ServerPlayer player) {
        player.removeEffect(ModMobEffects.FREE_PLAY);
    }

    /**
     * Make the badge agree with the run, in whichever direction it currently disagrees.
     *
     * <p>The effect is infinite and saved on the player, but nothing used to take it off again —
     * so a run that <em>stopped</em> being Free Play (custom content disabled, a config restored)
     * kept the icon for the life of the save, and every player reasonably read that as "still
     * stuck in Free Play". Reconciling on login, on respawn, and after anything that can change
     * the answer is what closes that: existing saves carrying an orphan badge shed it the next
     * time they log in.</p>
     */
    public static void reconcileFreePlayEffect(ServerPlayer player) {
        if (isCheated(player)) {
            applyFreePlayEffect(player);
        } else {
            clearFreePlayEffect(player);
        }
    }

    /**
     * Should this advancement be written to the cross-world profile for this
     * player right now? Yes when the run is clean, OR when it's an
     * editor-authoring advancement (mode-agnostic). The display-present check
     * stays at the call site ({@code AchievementEvents.shouldPersist}).
     */
    public static boolean persistsAdvancement(ServerPlayer player, AdvancementHolder holder) {
        return !isCheated(player) || isEditorAdvancement(holder.id());
    }

    /**
     * Editor-authoring advancements ({@code dungeontrain:editor/*}) are
     * mode-agnostic — authoring legitimately happens in creative — so they
     * persist even in a cheated run. Package-private for unit tests.
     */
    static boolean isEditorAdvancement(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace())
            && id.getPath().startsWith("editor/");
    }
}
