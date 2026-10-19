package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The item drawn beside each community reward, on the Contribute card and the rewards page. Kept
 * apart from {@link CommunityRewards} so the shelf's rules stay testable without a Minecraft
 * bootstrap (touching {@link Items} needs one).
 */
public final class RewardIcons {

    private RewardIcons() {}

    public static ItemStack of(CommunityRewards.Reward reward) {
        return switch (reward) {
            case QUICKER_STREAMS -> new ItemStack(Items.CLOCK);                 // less delay
            case VOICE_ON_STREAM -> new ItemStack(Items.GOAT_HORN);             // a voice carried far
            case TWO_STREAMS -> new ItemStack(ModItems.LIVE_HEADPIECE.get());   // two Live Eyes, stacked
            case HD_STREAMS -> new ItemStack(Items.SPYGLASS);                   // a sharper view
            case REPLAYS_KEPT -> new ItemStack(Items.RECOVERY_COMPASS);         // back to where you were
        };
    }

    /**
     * Draw {@code reward}'s icon in the 16 px square at ({@code x}, {@code y}). Two Streams draws two
     * Live Eyes, the back one up and right, the front one down and left, lifted so they layer cleanly.
     */
    public static void render(GuiGraphics g, CommunityRewards.Reward reward, int x, int y) {
        ItemStack stack = of(reward);
        if (reward != CommunityRewards.Reward.TWO_STREAMS) {
            g.renderItem(stack, x, y);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, -20);
        g.renderItem(stack, x + 3, y - 1);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 20);
        g.renderItem(stack, x - 1, y + 3);
        g.pose().popPose();
    }
}
