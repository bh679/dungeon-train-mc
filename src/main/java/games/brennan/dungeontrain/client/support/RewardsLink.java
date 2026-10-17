package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The one way into the Community Rewards page: the Live Eye death pages' link and button, and
 * {@code /dungeontrain-rewards}, the client command a chat line's aqua link runs (vanilla chat clicks
 * can run a command and nothing else).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RewardsLink {

    public static final String COMMAND = "/dungeontrain-rewards";

    private RewardsLink() {}

    /** Open the Community Rewards page over {@code parent}; Done returns there. */
    public static void open(@Nullable Screen parent) {
        // TODO: open CommunityRewardsScreen once the "Community Rewards page and sales" session lands it.
        Minecraft.getInstance().setScreen(new SupportScreen(parent));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dungeontrain-rewards").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            // Runs from the chat screen: swap screens on the next tick so the chat input closes first.
            mc.execute(() -> open(null));
            return 1;
        }));
    }
}
