package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for {@link BookViewScreen}'s private {@code currentPage} field, so the book-read
 * telemetry ({@link games.brennan.dungeontrain.client.BookReadClientEvents}) can sample which page the
 * player is on each client tick — without a getter, this private field is otherwise unreachable.
 *
 * <p>Applies to {@code LecternScreen} too (it extends {@code BookViewScreen}), though the telemetry
 * only tracks held-book reads.</p>
 */
@Mixin(BookViewScreen.class)
public interface BookViewScreenAccessor {

    @Accessor("currentPage")
    int dungeontrain$getCurrentPage();
}
