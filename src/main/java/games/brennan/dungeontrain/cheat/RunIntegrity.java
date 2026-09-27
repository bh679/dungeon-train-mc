package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.FreePlayReport;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.FreePlayCausePacket;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

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
 * <p>The taint also arrives session-wide, without touching the player, from
 * {@link AisDataIntegrity} (modified AIS config), {@link CheatModIntegrity}
 * (known cheat mods) and {@link EditorContentIntegrity} (custom Train Editor
 * content). Those clear themselves when the cause goes away. Two more arrive
 * world-wide and do <em>not</em> clear: {@link PortalTuningIntegrity} (a retuned
 * portal rate) and {@link KeepInventoryIntegrity} ({@code keepInventory} on).</p>
 *
 * <p>{@link OperatorIntegrity} (someone online has cheats) is session-wide too,
 * but it is the one source that also stamps the player: each operator's own run
 * is marked permanently, so {@code /op} → cheat → {@code /deop} can't launder
 * it. Everyone else goes clean again once no operator is online.</p>
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

    /**
     * How many changed settings / detected mods a cause's detail line names before it stops
     * listing and starts counting. A heavily-retuned config can deviate on dozens of keys, and a
     * tooltip is not the place to read all of them — the chat notice on join still lists the lot.
     */
    private static final int MAX_DETAIL_ITEMS = 3;

    private RunIntegrity() {}

    /**
     * One active reason this run is Free Play: the soft cause phrase the player was (or would have
     * been) told in chat, plus the specifics behind it where DT tracks any — which settings were
     * changed, which cheat mods were found.
     *
     * @param cause  a localized phrase naming what started Free Play
     * @param detail the specifics, already capped and formatted, or {@code null} when the cause has
     *               nothing further to say (a game-mode switch explains itself)
     */
    public record FreePlayCause(Component cause, @Nullable Component detail) {}

    /**
     * Is this player's current run Free Play? True when the run is permanently
     * cheated ({@link #isPermanentlyCheated}), OR when the whole server session
     * is Free Play because AIS data was changed
     * ({@link AisDataIntegrity#isSessionFreePlay}), DT's own balance config was
     * changed ({@link DtConfigIntegrity#isSessionFreePlay}), a known cheat mod is
     * installed ({@link CheatModIntegrity#isSessionFreePlay}), an unapproved mod
     * is installed ({@link UnapprovedModIntegrity#isSessionFreePlay}), custom Train
     * Editor content is active ({@link EditorContentIntegrity#isSessionFreePlay}),
     * or someone online has cheats ({@link OperatorIntegrity#isSessionFreePlay}),
     * OR the world's portal rate has been retuned
     * ({@link PortalTuningIntegrity#isWorldFreePlay}) or the world has run with
     * {@code keepInventory} on ({@link KeepInventoryIntegrity#isWorldFreePlay}) —
     * those last two per-world and permanent rather than per-session and derived,
     * see those classes.
     * Every persistence gate keys off this, so the session taints inherit all
     * Free Play behaviour.
     */
    public static boolean isCheated(ServerPlayer player) {
        return AisDataIntegrity.isSessionFreePlay()
            || DtConfigIntegrity.isSessionFreePlay()
            || CheatModIntegrity.isSessionFreePlay()
            || UnapprovedModIntegrity.isSessionFreePlay()
            || EditorContentIntegrity.isSessionFreePlay()
            || OperatorIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay()
            || KeepInventoryIntegrity.isWorldFreePlay()
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
            || UnapprovedModIntegrity.isSessionFreePlay()
            || OperatorIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay()
            || KeepInventoryIntegrity.isWorldFreePlay()
            || isPermanentlyCheated(player);
    }

    /**
     * Is the session <em>already visibly</em> Free Play — i.e. the player has been told so and has
     * the effect — for a reason that has nothing to do with them? Callers use this to skip a
     * confirmation prompt that would have nothing to confirm, and to record the permanent taint
     * quietly instead of notifying twice.
     *
     * <p>Covers the AIS-config, DT-config, custom-editor-content, operator-present,
     * retuned-portal-rate and {@code keepInventory} taints. Deliberately
     * <b>not</b> {@link CheatModIntegrity} — that source predates this helper and still takes the
     * prompt / notify path; folding it in would change its Discord reporting, which is a separate
     * call.</p>
     */
    public static boolean isVisiblySessionFreePlay() {
        return AisDataIntegrity.isSessionFreePlay()
            || DtConfigIntegrity.isSessionFreePlay()
            || EditorContentIntegrity.isSessionFreePlay()
            || OperatorIntegrity.isSessionFreePlay()
            || PortalTuningIntegrity.isWorldFreePlay()
            || KeepInventoryIntegrity.isWorldFreePlay();
    }

    public static void markCheated(ServerPlayer player, Component cause) {
        // Idempotence keys off the permanent attachment, NOT isCheated(): during
        // a session-only config taint a tainting action must still be recorded
        // permanently, or restoring the config would forget it.
        if (isPermanentlyCheated(player)) return;
        player.setData(ModDataAttachments.RUN_CHEATED.get(), Boolean.TRUE);
        // Record WHY, next to the flag and before the effect goes on: the badge's hover tooltip is
        // the only surface that can still answer that once the chat line below has scrolled away,
        // and applyFreePlayEffect syncs the answer to the client as it applies.
        player.setData(ModDataAttachments.FREE_PLAY_CAUSE.get(), cause);
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
        syncCauses(player);
    }

    /**
     * Take the {@code Free Play} marker off. Only ever correct once the run has genuinely stopped
     * being Free Play — {@code CheatDetectionEvents.onEffectRemove} still cancels any removal
     * while {@link #isCheated}, so this cannot be used to shed the badge on a cheated run.
     */
    public static void clearFreePlayEffect(ServerPlayer player) {
        player.removeEffect(ModMobEffects.FREE_PLAY);
        syncCauses(player);
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
     * Why is this run Free Play right now — every currently-active reason, in the order the login
     * notices announce them ({@code CheatDetectionEvents.onLogin}): changed AIS data, changed DT
     * config, a cheat mod, an unapproved mod, custom editor content, a retuned portal rate,
     * {@code keepInventory}, then the player's own recorded action.
     *
     * <p>Built from the same nine terms as {@link #isCheated}, so an empty list means exactly "not
     * Free Play" and the tooltip can never disagree with the badge it explains. Usually one entry;
     * a creative switch made <em>inside</em> an already-tainted session genuinely has two reasons
     * and lists both.</p>
     */
    public static List<FreePlayCause> freePlayCauses(ServerPlayer player) {
        List<FreePlayCause> causes = new ArrayList<>();
        if (AisDataIntegrity.isSessionFreePlay()) {
            causes.add(sessionCause("ais_data", AisDataIntegrity.deviations()));
        }
        if (DtConfigIntegrity.isSessionFreePlay()) {
            causes.add(sessionCause("dt_config", DtConfigIntegrity.deviations()));
        }
        if (CheatModIntegrity.isSessionFreePlay()) {
            causes.add(sessionCause("cheat_mod", CheatModIntegrity.detected()));
        }
        if (UnapprovedModIntegrity.isSessionFreePlay()) {
            causes.add(sessionCause("unapproved_mod", UnapprovedModIntegrity.detected()));
        }
        if (EditorContentIntegrity.isSessionFreePlay()) {
            causes.add(sessionCause("custom_content", EditorContentIntegrity.contentPackageNames()));
        }
        if (OperatorIntegrity.isSessionFreePlay()) {
            // Names the operators, which matters most here: this is the one taint a player can be
            // under because of somebody ELSE, so a badge with no reason reads as arbitrary.
            causes.add(sessionCause("operator", OperatorIntegrity.detected()));
        }
        if (PortalTuningIntegrity.isWorldFreePlay()) {
            causes.add(sessionCause("portal_rate", List.of()));
        }
        if (KeepInventoryIntegrity.isWorldFreePlay()) {
            causes.add(sessionCause("keep_inventory", List.of()));
        }
        if (isPermanentlyCheated(player)) {
            causes.add(new FreePlayCause(recordedCause(player), null));
        }
        return List.copyOf(causes);
    }

    /** Push the current answer to {@link #freePlayCauses} to this player's tooltip. */
    private static void syncCauses(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new FreePlayCausePacket(freePlayCauses(player)));
    }

    /** One of the session/world taints, whose cause phrase is a bare {@code cause.<key>} line. */
    private static FreePlayCause sessionCause(String key, List<String> details) {
        return new FreePlayCause(
            Component.translatable("chat.dungeontrain.free_play.cause." + key),
            detailLine(details));
    }

    /**
     * The cause recorded when this run was permanently tainted. Worlds tainted before the cause was
     * being stored have the flag and nothing else — they get a generic line, which is still a better
     * answer than a badge that explains nothing.
     */
    private static Component recordedCause(ServerPlayer player) {
        if (!player.hasData(ModDataAttachments.FREE_PLAY_CAUSE.get())) {
            return Component.translatable("effect.dungeontrain.free_play.trigger.unknown");
        }
        Component cause = player.getData(ModDataAttachments.FREE_PLAY_CAUSE.get());
        return cause.getString().isEmpty()
            ? Component.translatable("effect.dungeontrain.free_play.trigger.unknown")
            : cause;
    }

    /**
     * The specifics behind a cause — changed settings, detected mods — as one line, listing at most
     * {@link #MAX_DETAIL_ITEMS} and counting the rest. {@code null} when there are none.
     * Package-private for unit tests.
     */
    @Nullable
    static Component detailLine(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() <= MAX_DETAIL_ITEMS) {
            return Component.translatable("effect.dungeontrain.free_play.trigger.detail",
                String.join(", ", items));
        }
        return Component.translatable("effect.dungeontrain.free_play.trigger.detail_more",
            String.join(", ", items.subList(0, MAX_DETAIL_ITEMS)),
            items.size() - MAX_DETAIL_ITEMS);
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
