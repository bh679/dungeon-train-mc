package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.ChunkRoomPartsClient;
import games.brennan.dungeontrain.net.ChunkRoomPartsSyncPacket;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which chunk parts frame a dimensional carriage room: per kind, the parts it draws from (with
 * their weights and a Remove), and an Add picker. A frame is all four kinds together, so the screen
 * says when one is missing — the room then stands unframed in its skybox.
 *
 * <p>Every change is a {@code dungeontrain editor chunkpart add|remove} command; the server answers
 * each with a fresh {@link ChunkRoomPartsSyncPacket}, which this screen reads on its next rebuild.</p>
 */
public final class ChunkRoomPartsScreen implements MenuScreen {

    static final String COMMAND = "dungeontrain editor chunkpart";

    private final String room;

    public ChunkRoomPartsScreen(String room) {
        this.room = room;
    }

    @Override
    public String title() {
        return "Chunk Parts — " + room;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        ChunkRoomPartsClient.requestThrottled(room);
        List<CommandMenuEntry> out = new ArrayList<>();
        Optional<ChunkRoomPartsSyncPacket> snap = ChunkRoomPartsClient.snapshot(room);
        if (snap.isEmpty()) {
            out.add(new CommandMenuEntry.Loading("Loading…"));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
        boolean complete = true;
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            out.add(new CommandMenuEntry.Label(kindLabel(kind)));
            int count = 0;
            for (ChunkRoomPartsSyncPacket.Assigned a : snap.get().assigned()) {
                if (!a.kind().equals(kind.id())) continue;
                count++;
                String add = COMMAND + " add " + room + " " + kind.id() + " " + a.name() + " ";
                out.add(new CommandMenuEntry.Quad(
                    new CommandMenuEntry.Label(a.name() + "  ×" + a.weight()),
                    new CommandMenuEntry.Stay("−", add + Math.max(1, a.weight() - 1)),
                    new CommandMenuEntry.Stay("+", add + Math.min(100, a.weight() + 1)),
                    new CommandMenuEntry.Stay("Remove",
                        COMMAND + " remove " + room + " " + kind.id() + " " + a.name()),
                    0.55, 0.65, 0.75));
            }
            if (count == 0) complete = false;
            out.add(new CommandMenuEntry.DrillIn("+ Add " + kindLabel(kind).toLowerCase(Locale.ROOT),
                new Picker(room, kind)));
        }
        if (!complete) {
            out.add(new CommandMenuEntry.Label("Needs all four kinds to be framed"));
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    static String kindLabel(ChunkPartKind kind) {
        String n = kind.name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Every saved part of one kind; picking one adds it to the room at weight 1. */
    static final class Picker implements MenuScreen {
        private final String room;
        private final ChunkPartKind kind;

        Picker(String room, ChunkPartKind kind) {
            this.room = room;
            this.kind = kind;
        }

        @Override
        public String title() {
            return "Add " + kindLabel(kind).toLowerCase(Locale.ROOT) + " part — " + room;
        }

        @Override
        public List<CommandMenuEntry> entries() {
            List<CommandMenuEntry> out = new ArrayList<>();
            Optional<ChunkRoomPartsSyncPacket> snap = ChunkRoomPartsClient.snapshot(room);
            if (snap.isPresent()) {
                for (ChunkRoomPartsSyncPacket.Available a : snap.get().available()) {
                    if (!a.kind().equals(kind.id())) continue;
                    boolean assigned = snap.get().assigned().stream()
                        .anyMatch(x -> x.kind().equals(a.kind()) && x.name().equals(a.name()));
                    // Already drawn from: shown, not re-added — adding again would reset its weight.
                    out.add(assigned ? new CommandMenuEntry.Label(a.name() + "  ✓")
                        : new CommandMenuEntry.Stay(a.name(), COMMAND + " add " + room + " " + kind.id() + " " + a.name()));
                }
            }
            if (out.isEmpty()) out.add(new CommandMenuEntry.Label("No saved parts of this kind yet"));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
    }
}
