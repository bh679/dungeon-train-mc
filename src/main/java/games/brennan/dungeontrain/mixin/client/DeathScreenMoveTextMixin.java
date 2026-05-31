package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Relocates the vanilla {@link DeathScreen} subtitle ("&lt;name&gt; was killed by …")
 * and suppresses the meaningless "Score: 0" line so they don't collide with
 * Dungeon Train's run-stats panel rendered by {@code DeathScreenLayoutHandler}.
 *
 * <p>Vanilla draws the subtitle at {@code y=85} and the score at {@code y=100}
 * (both absolute screen coords). Our stats panel anchors above the button row
 * and on high-GUI-scale windows that anchor sits at roughly {@code y=70-80},
 * which would put both vanilla lines INSIDE the panel.</p>
 *
 * <p>Subtitle is moved to {@code y=78} — below the 2× scaled "You Died!"
 * title (which spans ~y=30–70) and above the run-stats panel. The vanilla
 * mouseover tooltip that uses the same {@code y=85} constant for hit-testing
 * rides along with the same redirection, so the tooltip activates at the
 * relocated subtitle position — fine, since that's where the cause-of-death
 * text now renders and the tooltip just repeats the same info.</p>
 *
 * <p>Score line is shifted off-screen ({@code y=-999}). "Score" in vanilla
 * tracks XP collected; Dungeon Train doesn't surface that, so the line is
 * always "Score: 0" — pure visual clutter.</p>
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMoveTextMixin {

    @ModifyConstant(method = "render", constant = @Constant(intValue = 85))
    private int dungeontrain$moveSubtitleY(int original) {
        return 78;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 100))
    private int dungeontrain$hideScoreY(int original) {
        return -999;
    }
}
