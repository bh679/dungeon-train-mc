package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.net.BuilderDirtyPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side answer to "does the builder have unsaved changes?"
 *
 * <p>Read by the pause menu to tint <b>Save</b> green. A switch-prompt packet also lands here and
 * opens the Save / Discard / Go Back screen, because the reply arrives asynchronously — the click
 * that asked for it is long over by then.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderDirtyState {

    private static volatile int dirtyCount = 0;

    private BuilderDirtyState() {}

    public static void accept(BuilderDirtyPacket packet) {
        dirtyCount = packet.dirtyCount();
        if (packet.isSwitchPrompt()) {
            BuilderMode.fromId(packet.targetModeId()).ifPresent(mode ->
                Minecraft.getInstance().setScreen(new BuilderSwitchConfirmScreen(
                    Minecraft.getInstance().screen, mode, packet.dirtyCount())));
        }
    }

    public static int dirtyCount() {
        return dirtyCount;
    }

    public static boolean hasUnsavedChanges() {
        return dirtyCount > 0;
    }

    public static void clear() {
        dirtyCount = 0;
    }
}
