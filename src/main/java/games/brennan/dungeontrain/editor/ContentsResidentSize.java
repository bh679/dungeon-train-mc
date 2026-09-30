package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Which {@link ContentsSize} the Contents editor has standing in the sky.
 *
 * <p>Room, Half and Full contents are separate template types, laid out from the same origin the
 * way every category is ({@link EditorLayout}) — so, like the categories themselves, only one size
 * is stamped at a time, and switching to a template of another size erases this one's plots and
 * stamps that one's ({@link CarriageContentsEditor#ensureResident}). This is the answer to "which
 * contents plot is at this position" once the category is known.</p>
 *
 * <p>Mirrored into {@link DungeonTrainWorldData} for the same reason
 * {@link EditorStampedCategoryState} is: the plots are still standing after a restart.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ContentsResidentSize {

    private static volatile ContentsSize current = ContentsSize.ROOM;

    private ContentsResidentSize() {}

    public static ContentsSize current() {
        return current;
    }

    public static void set(ServerLevel overworld, ContentsSize size) {
        current = size;
        if (overworld != null) {
            DungeonTrainWorldData.get(overworld).setEditorContentsSize(
                size == ContentsSize.ROOM ? "" : size.key());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null) return;
        current = ContentsSize.parse(DungeonTrainWorldData.get(overworld).editorContentsSize())
            .orElse(ContentsSize.ROOM);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        current = ContentsSize.ROOM;
    }
}
