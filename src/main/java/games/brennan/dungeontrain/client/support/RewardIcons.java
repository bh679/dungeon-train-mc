package games.brennan.dungeontrain.client.support;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The vanilla item drawn beside each community reward, on the Contribute card and the rewards page.
 * Kept apart from {@link CommunityRewards} so the shelf's rules stay testable without a Minecraft
 * bootstrap (touching {@link Items} needs one).
 */
public final class RewardIcons {

    private RewardIcons() {}

    public static ItemStack of(CommunityRewards.Reward reward) {
        return switch (reward) {
            case QUICKER_STREAMS -> new ItemStack(Items.CLOCK);           // less delay
            case VOICE_ON_STREAM -> new ItemStack(Items.GOAT_HORN);       // a voice carried far
            case TWO_STREAMS -> new ItemStack(Items.OBSERVER);            // a second eye on the train
            case HD_STREAMS -> new ItemStack(Items.SPYGLASS);             // a sharper view
            case REPLAYS_KEPT -> new ItemStack(Items.RECOVERY_COMPASS);   // back to where you were
        };
    }
}
