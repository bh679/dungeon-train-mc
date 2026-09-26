package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.ChunkFrameRoomsClient;
import games.brennan.dungeontrain.net.ChunkFrameRoomsSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which chunk dimensions a frame dresses — one toggle per chunk-dimension room variant, with All and
 * None, and the frame's weight against the other frames dressing the same room. The frame-side twin of
 * a template's stage picker.
 */
public final class ChunkFrameRoomsScreen implements MenuScreen {

    private static final String COMMAND = "dungeontrain editor chunkframe";
    private static final int MAX_WEIGHT = 100;

    private final String frame;

    public ChunkFrameRoomsScreen(String frame) {
        this.frame = frame;
    }

    @Override
    public String title() {
        return "Chunk dimensions — " + frame;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        ChunkFrameRoomsClient.requestThrottled(frame);
        List<CommandMenuEntry> out = new ArrayList<>();
        Optional<ChunkFrameRoomsSyncPacket> snap = ChunkFrameRoomsClient.snapshot(frame);
        if (snap.isEmpty()) {
            out.add(new CommandMenuEntry.Loading("Loading…"));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
        ChunkFrameRoomsSyncPacket s = snap.get();
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Stay("All", COMMAND + " rooms " + frame + " all", s.all()),
            new CommandMenuEntry.Stay("None", COMMAND + " rooms " + frame + " none", s.selected().isEmpty()),
            0.50));
        for (String room : s.rooms()) {
            boolean on = s.selected().contains(room);
            String cmd = COMMAND + " room " + frame + " " + room + " ";
            out.add(new CommandMenuEntry.Toggle(room, on, cmd + "on", cmd + "off"));
        }
        String weight = COMMAND + " weight " + frame + " ";
        out.add(new CommandMenuEntry.Triple(
            new CommandMenuEntry.Stay("−", weight + Math.max(1, s.weight() - 1)),
            new CommandMenuEntry.Label("Weight ×" + s.weight()),
            new CommandMenuEntry.Stay("+", weight + Math.min(MAX_WEIGHT, s.weight() + 1)),
            0.25, 0.75));
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }
}
