package games.brennan.dungeontrain.perks.effects.crafter;

import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Duck interface the furnace block-entity mixin implements: the player whose Cooking Time rank the
 * block cooks with. Ownership is "whoever last opened the menu", kept in memory only (a chunk
 * reload forgets it until the next open).
 */
public interface CrafterCookingOwner {

    void dungeontrain$setCookingOwner(Player player);

    /** The current cook's UUID, or null when nobody has opened the block since it loaded. */
    UUID dungeontrain$cookingOwner();
}
