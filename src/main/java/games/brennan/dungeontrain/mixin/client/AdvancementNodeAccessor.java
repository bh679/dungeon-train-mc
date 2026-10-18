package games.brennan.dungeontrain.mixin.client;

import net.minecraft.advancements.AdvancementNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Lets the client move an advancement under a newly revealed parent in its own copy of the tree, without
 * re-adding it (which would replay its toast). See {@code AdvancementGapsClient#applyRelinks}.
 */
@Mixin(AdvancementNode.class)
public interface AdvancementNodeAccessor {

    @Mutable
    @Accessor("parent")
    void dungeontrain$setParent(AdvancementNode parent);

    @Accessor("children")
    Set<AdvancementNode> dungeontrain$children();
}
