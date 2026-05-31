package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Relocates the vanilla {@link DeathScreen} subtitle ("&lt;name&gt; was killed by …")
 * and suppresses the meaningless "Score: 0" line so they don't collide with
 * Dungeon Train's run-stats panels rendered by {@code DeathScreenLayoutHandler}.
 *
 * <p>Vanilla draws the subtitle at {@code y=85} and the score at {@code y=100}
 * (both absolute screen coords). The scaled "You Died!" title spans roughly
 * {@code y=60–78} (drawn at unscaled y=30 with a 2× pose scale, font line
 * height 9). Placing the subtitle at {@code y=82} puts it cleanly below the
 * title with a 4 px gap, and leaves room for the stats panel to start at
 * {@code y=95+} without overlapping either piece of text.</p>
 *
 * <p>The vanilla mouseover tooltip that uses the same {@code y=85} constant
 * for hit-testing rides along with the same redirection, so the tooltip
 * activates around the new subtitle position — acceptable, since the
 * cause-of-death text now appears in plain sight at the same y and the
 * tooltip just repeats the same info.</p>
 *
 * <p>Score line is shifted off-screen ({@code y=-999}). "Score" in vanilla
 * tracks XP collected; Dungeon Train doesn't surface that, so the line is
 * always "Score: 0" — pure visual clutter.</p>
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMoveTextMixin {

    @ModifyConstant(method = "render", constant = @Constant(intValue = 85))
    private int dungeontrain$moveSubtitleY(int original) {
        return 82;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 100))
    private int dungeontrain$hideScoreY(int original) {
        return -999;
    }
}
