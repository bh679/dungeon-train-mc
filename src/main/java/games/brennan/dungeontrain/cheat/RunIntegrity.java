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
     * ({@link AisDataIntegrity#isSessionFreePlay}). Every persistence gate keys
     * off this, so the session taint inherits all Free Play behaviour.
     */
    public static boolean isCheated(ServerPlayer player) {
        return AisDataIntegrity.isSessionFreePlay() || isPermanentlyCheated(player);
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
    public static void markCheated(ServerPlayer player, Component cause) {
        // Idempotence keys off the permanent attachment, NOT isCheated(): during
        // a session-only AIS taint a tainting action must still be recorded
        // permanently, or restoring the AIS config would forget it.
        if (isPermanentlyCheated(player)) return;
        player.setData(ModDataAttachments.RUN_CHEATED.get(), Boolean.TRUE);
        applyFreePlayEffect(player);
        LOGGER.info("[DungeonTrain] Run is now Free Play for {} — {}",
            player.getName().getString(), cause.getString());
        if (AisDataIntegrity.isSessionFreePlay()) {
            // Already visibly in Free Play this session (AIS notice on join) —
            // record the permanent taint quietly, no second chat line / Discord post.
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
