package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Decides whether the {@code dungeontrain:editor/*} advancement tree is shown in the
 * advancements screen (L).
 *
 * <p>The editor tree is a builder-mode tab — every one of its advancements is earned by
 * doing something in the Train Editor, which is only reachable in Creative. A plain
 * survival player can never earn any of it, so the tab sits next to the real progression
 * tree permanently empty. It is already excluded from the hint system
 * ({@code AchievementEvents}) and from the completionist capstone
 * ({@code CompletionistAdvancement}); this is the screen-side half of the same split.</p>
 *
 * <p>Shown when the local player is in Creative (which covers the builder world and any
 * creative session), or when the run is already Free Play — custom editor content is one
 * of the things that turns Free Play on, so a tainted survival run keeps the tab.</p>
 *
 * <p>Read by the two screen mixins —
 * {@code games.brennan.dungeontrain.mixin.client.AdvancementsScreenEditorTabMixin} and
 * {@code games.brennan.dungeontrain.mixin.betteradvancements.BetterAdvancementsScreenEditorTabMixin}
 * — at the point each screen would build a tab for a root advancement. Both screens are
 * constructed fresh on every open, so the answer is re-evaluated each time L is pressed and
 * a {@code /gamemode} switch takes effect without a relog.</p>
 */
public final class EditorAdvancementsGate {

    /** Path prefix of the editor tree, shared with the root {@code dungeontrain:editor/root}. */
    private static final String EDITOR_PATH_PREFIX = "editor/";

    private EditorAdvancementsGate() {}

    /**
     * Is this advancement id part of the editor tree? Client-side twin of
     * {@code RunIntegrity.isEditorAdvancement} (server-side, decides persistence in Free
     * Play) — keep the two in step if the tree ever moves.
     */
    public static boolean isEditorAdvancement(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace())
            && id.getPath().startsWith(EDITOR_PATH_PREFIX);
    }

    /**
     * Should the editor tree be hidden from the advancements screen right now?
     *
     * <p>Hidden in Survival, Adventure and Spectator, unless the run is Free Play. With no
     * local player (screen open outside a level — shouldn't happen) nothing is hidden: the
     * gate only ever removes a tab on a positive signal.</p>
     */
    public static boolean shouldHideEditorTab() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (player.isCreative()) return false;
        // The Free Play badge is the client-visible half of RunIntegrity's taint — the same
        // signal FreePlayTooltip reads.
        return !player.hasEffect(ModMobEffects.FREE_PLAY);
    }
}
