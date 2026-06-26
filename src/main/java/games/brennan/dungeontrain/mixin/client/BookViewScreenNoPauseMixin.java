package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Makes the vanilla written-book reader ({@link BookViewScreen}) non-pausing.
 *
 * <p>Vanilla {@code Screen.isPauseScreen()} defaults to {@code true} and
 * {@code BookViewScreen} does not override it, so reading a book in
 * singleplayer pauses the integrated server and triggers a full autosave.
 * On a moving Sable train that autosave shuffles the train's sub-levels
 * through holding chunks and the resume (book close) fails to restore the
 * sub-levels around the player, making nearby carriages vanish until a
 * world reload. Reading a book should not pause a run mid-ride, so we add
 * the {@code isPauseScreen() == false} override Mojang omitted.</p>
 *
 * <p>Singleplayer-only effect by nature — multiplayer never pauses on a
 * screen. Trade-off: the world keeps ticking while the book is open, so the
 * player can take damage / be attacked while reading (acceptable, arguably
 * more correct, on a constantly-moving roguelite train).</p>
 *
 * <p>Implemented as an added override (a method merge), not an {@code @Inject}
 * — {@code BookViewScreen} inherits {@code isPauseScreen()} from {@code Screen}
 * rather than declaring it, so an {@code @Inject} targeting this class would
 * fail to resolve at apply-time. Extending {@code Screen} lets the compiler
 * verify the {@code @Override}; the private constructor is mixin boilerplate
 * to satisfy {@code Screen}'s {@code protected Screen(Component)} ctor and is
 * discarded at apply-time.</p>
 */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenNoPauseMixin extends Screen {

    private BookViewScreenNoPauseMixin(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
