package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsPools;
import games.brennan.dungeontrain.train.CarriageContentsWeights;

import java.util.Locale;
import java.util.Optional;

/**
 * The line a contents plot's panel draws under its name when the plot sits in a {@link CarriageContentsPools
 * pool}: a pooled group's share of the pool's slot ({@code "Enemies · 30%"}), or a room's share of the pooled
 * group it belongs to ({@code "Nether · 13%"}). Shares are with every room open, the same reading the in-world
 * menu's share column gives. Empty for everything else.
 */
final class ContentsPoolNotes {

    private ContentsPoolNotes() {}

    static String noteFor(String contentsId) {
        if (contentsId == null) return "";
        String id = contentsId.toLowerCase(Locale.ROOT);
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        CarriageContentsPools.Pool pool = CarriageContentsPools.poolOf(id);
        if (pool != null) {
            int total = 0;
            for (String member : pool.members()) total += Math.max(0, weights.weightFor(member));
            return pool.name() + " · " + percent(weights.weightFor(id), total);
        }
        for (CarriageContentsPools.Pool p : CarriageContentsPools.all()) {
            for (String groupId : p.members()) {
                Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(groupId);
                if (group.isEmpty()) continue;
                String note = roomNote(group.get(), id, weights.nameFor(groupId));
                if (note != null) return note;
            }
        }
        return "";
    }

    /** {@code roomId}'s share of {@code group} under {@code groupName}, or null when it is not a member. */
    private static String roomNote(CarriageContentsGroup group, String roomId, String groupName) {
        int total = Math.max(0, group.selfWeight());
        Integer own = null;
        for (CarriageContentsGroup.Member m : group.members()) {
            total += Math.max(0, m.weight());
            if (m.id().equalsIgnoreCase(roomId)) own = m.weight();
        }
        return own == null ? null : groupName + " · " + percent(own, total);
    }

    private static String percent(int weight, int total) {
        return (total <= 0 ? 0 : Math.round(100f * Math.max(0, weight) / total)) + "%";
    }
}
