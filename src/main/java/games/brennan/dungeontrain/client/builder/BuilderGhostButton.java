package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The ghost-train toggle in the builder pause menu's tools column.
 *
 * <p>Tinted green while on and leaves the menu open on click, both for {@link BuilderMirrorButton}'s
 * reasons — it reads as one of the shape toggles it sits under, and a toggle row you have to reopen
 * the menu to use twice is a toggle row nobody uses twice.</p>
 *
 * <p>Unlike the mirror cells there is no command and no server round trip: the ghosts are drawn on
 * the client out of blocks the client can already see, so the whole toggle is a client config value
 * that {@code BuilderGhostTrainRenderer} reads on its next sweep. That also makes it a per-player
 * preference on a shared world, which is the right scope for "what do I want to look at".</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostButton extends PauseMenuActionButton {

    /** Same green the mirror cells light with, so the two read as one kind of control. */
    private static final float ON_R = 0.35F;
    private static final float ON_G = 1.0F;
    private static final float ON_B = 0.35F;

    public BuilderGhostButton(int x, int y, int width, int height, Component label) {
        super(x, y, width, height, label, 1.0F, 1.0F, 1.0F, /*visibleWhenShift*/ false,
                b -> ClientDisplayConfig.setBuilderGhostTrainEnabled(
                        !ClientDisplayConfig.isBuilderGhostTrainEnabled()));
    }

    private boolean lit() {
        return ClientDisplayConfig.isBuilderGhostTrainEnabled();
    }

    @Override
    protected float tintR() {
        return lit() ? ON_R : 1.0F;
    }

    @Override
    protected float tintG() {
        return lit() ? ON_G : 1.0F;
    }

    @Override
    protected float tintB() {
        return lit() ? ON_B : 1.0F;
    }
}
