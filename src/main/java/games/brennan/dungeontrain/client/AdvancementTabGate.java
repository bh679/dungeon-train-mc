package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Decides which tabs the advancements screen (L) builds.
 *
 * <ul>
 *   <li><b>Other mods' tabs</b> — Minecraft's own and every other namespace's — are hidden while
 *       {@link ClientDisplayConfig#isHideOtherAdvancementTabs()} is on (the default), so the screen is
 *       Dungeon Train's tabs only. Their advancements are still earned, toasted and persisted; only the
 *       tab is not drawn. Sibling mods count as other mods.</li>
 *   <li><b>The editor tree</b> ({@code dungeontrain:editor/*}) is a builder-mode tab: every one of its
 *       advancements is earned in the Train Editor, which is only reachable in Creative. It is shown in
 *       Creative, or once the run is Free Play (custom editor content is one of the things that turns
 *       Free Play on, so a tainted survival run keeps the tab). It is already excluded from the hint
 *       system ({@code AchievementEvents}) and the completionist capstone
 *       ({@code CompletionistAdvancement}); this is the screen-side half of the same split.</li>
 * </ul>
 *
 * <p>Read by the two screen mixins —
 * {@code games.brennan.dungeontrain.mixin.client.AdvancementsScreenEditorTabMixin} and
 * {@code games.brennan.dungeontrain.mixin.betteradvancements.BetterAdvancementsScreenEditorTabMixin}
 * — at the point each screen would build a tab for a root advancement. Both screens are constructed
 * fresh on every open, so the answer is re-evaluated each time L is pressed: a {@code /gamemode} switch
 * or a change in Options → Dungeon Train takes effect without a relog.</p>
 */
public final class AdvancementTabGate {

    /** Path prefix of the editor tree, shared with the root {@code dungeontrain:editor/root}. */
    private static final String EDITOR_PATH_PREFIX = "editor/";

    private AdvancementTabGate() {}

    /** Should the screen leave out the tab whose root is {@code rootId}? */
    public static boolean shouldHideTab(ResourceLocation rootId) {
        return isHiddenOtherTab(rootId, ClientDisplayConfig.isHideOtherAdvancementTabs())
            || (isEditorAdvancement(rootId) && shouldHideEditorTab());
    }

    /** The namespace half of {@link #shouldHideTab} — pure, for tests. */
    static boolean isHiddenOtherTab(ResourceLocation rootId, boolean hideOthers) {
        return hideOthers && !DungeonTrain.MOD_ID.equals(rootId.getNamespace());
    }

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
     * Should the editor tree be hidden right now? Hidden in Survival, Adventure and Spectator, unless
     * the run is Free Play. With no local player (screen open outside a level — shouldn't happen)
     * nothing is hidden: the gate only ever removes a tab on a positive signal.
     */
    static boolean shouldHideEditorTab() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (player.isCreative()) return false;
        // The Free Play badge is the client-visible half of RunIntegrity's taint — the same
        // signal FreePlayTooltip reads.
        return !player.hasEffect(ModMobEffects.FREE_PLAY);
    }
}
