package games.brennan.dungeontrain.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Lets {@code PhotoProgressNotice} ask whether an advancement is on a player's advancement screen.
 *
 * <p>{@code PlayerAdvancements.visible} is the set last sent to the client, already shaped by
 * {@link AdvancementVisibilityEvaluatorMixin} — so a hidden advancement is in it only once revealed.</p>
 */
@Mixin(PlayerAdvancements.class)
public interface PlayerAdvancementsVisibleAccessor {

    @Accessor("visible")
    Set<AdvancementHolder> dungeontrain$getVisible();
}
