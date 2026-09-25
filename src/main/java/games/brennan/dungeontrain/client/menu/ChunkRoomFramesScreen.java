package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.ChunkRoomFramesClient;
import games.brennan.dungeontrain.net.ChunkRoomFramesSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which frames a dimensional carriage room draws from: each with its weight, − / + and Remove, and an
 * Add picker. Each pair picks one frame by weight; a room with none stands unframed in its skybox.
 *
 * <p>Every change is a {@code dungeontrain editor chunkframe add|remove} command; the server answers
 * each with a fresh {@link ChunkRoomFramesSyncPacket}, which this screen reads on its next rebuild.</p>
 */
public final class ChunkRoomFramesScreen implements MenuScreen {

    static final String COMMAND = "dungeontrain editor chunkframe";
    private static final int MAX_WEIGHT = 100;

    private final String room;

    public ChunkRoomFramesScreen(String room) {
        this.room = room;
    }

    @Override
    public String title() {
        return "Frames — " + room;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        ChunkRoomFramesClient.requestThrottled(room);
        List<CommandMenuEntry> out = new ArrayList<>();
        Optional<ChunkRoomFramesSyncPacket> snap = ChunkRoomFramesClient.snapshot(room);
        if (snap.isEmpty()) {
            out.add(new CommandMenuEntry.Loading("Loading…"));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
        if (snap.get().assigned().isEmpty()) {
            out.add(new CommandMenuEntry.Label("No frame — the room stands in its skybox"));
        }
        for (ChunkRoomFramesSyncPacket.Assigned a : snap.get().assigned()) {
            String set = COMMAND + " add " + room + " " + a.name() + " ";
            out.add(new CommandMenuEntry.Quad(
                new CommandMenuEntry.Label(a.name() + "  ×" + a.weight()),
                new CommandMenuEntry.Stay("−", set + Math.max(1, a.weight() - 1)),
                new CommandMenuEntry.Stay("+", set + Math.min(MAX_WEIGHT, a.weight() + 1)),
                new CommandMenuEntry.Stay("Remove", COMMAND + " remove " + room + " " + a.name()),
                0.55, 0.65, 0.75));
        }
        out.add(new CommandMenuEntry.DrillIn("+ Add frame", new Picker(room)));
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /** Every saved frame; picking one adds it to the room at weight 1. */
    static final class Picker implements MenuScreen {
        private final String room;

        Picker(String room) {
            this.room = room;
        }

        @Override
        public String title() {
            return "Add frame — " + room;
        }

        @Override
        public List<CommandMenuEntry> entries() {
            List<CommandMenuEntry> out = new ArrayList<>();
            Optional<ChunkRoomFramesSyncPacket> snap = ChunkRoomFramesClient.snapshot(room);
            if (snap.isPresent()) {
                for (String name : snap.get().available()) {
                    boolean assigned = snap.get().assigned().stream().anyMatch(x -> x.name().equals(name));
                    // Already drawn from: shown, not re-added — adding again would reset its weight.
                    out.add(assigned ? new CommandMenuEntry.Label(name + "  ✓")
                        : new CommandMenuEntry.Stay(name, COMMAND + " add " + room + " " + name));
                }
            }
            if (out.isEmpty()) out.add(new CommandMenuEntry.Label("No saved frames yet"));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
    }
}
