package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ShellPool;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Where each carriage plot stands in the editor: one {@code +X} row per {@link Row} — Rooms at
 * {@code Z 0}, then Halves, Groups, Flatbeds and Portals each one row further toward {@code -Z} —
 * each row its own plots laid end to end at their own lengths, a {@link EditorLayout#GAP} apart.
 * The part rows keep {@code +Z}.
 *
 * <p>The flatbed and the portal templates are placed by fixed rules rather than drawn by weight, so
 * they stand in rows of their own; every other template stands in its pool's row, by its effective
 * size (a Group carriage still in the Room folder from before the pools existed stands with the
 * Groups). Memoised on everything a position depends on;
 * {@link #rows(String[], Function, ToIntFunction, int)} is the pure layout, testable without a world.</p>
 */
public final class CarriagePlotRows {

    /** The template rows, in {@code -Z} order. */
    public enum Row {
        ROOMS, HALVES, GROUPS, FLATBEDS, PORTALS;

        /** The row a template of {@code pool} stands in. */
        public static Row of(ShellPool pool) {
            return switch (pool) {
                case ROOM -> ROOMS;
                case HALF -> HALVES;
                case GROUP -> GROUPS;
                case FLATBED -> FLATBEDS;
            };
        }
    }

    /** One row's spacing toward {@code -Z} for carriages {@code width} wide: a carriage plus the gap. */
    public static int rowStepZ(int width) {
        return width + EditorLayout.GAP;
    }

    /** The laid-out rows: each id's X start and row, and where each row's next plot would go. */
    public record Rows(Map<String, Integer> startX, Map<String, Row> rowOf, Map<Row, Integer> endX) {
        public int endOf(Row row) {
            return endX.getOrDefault(row, 0);
        }
    }

    private CarriagePlotRows() {}

    /** The {@code Z} of {@code row}, for carriages {@code width} wide. */
    public static int rowZ(Row row, int width) {
        return -row.ordinal() * rowStepZ(width);
    }

    /** The row {@code variant}'s plot stands in. */
    public static Row rowOf(CarriageVariant variant) {
        if (variant instanceof CarriageVariant.Builtin b
                && b.type() == CarriagePlacer.CarriageType.FLATBED) {
            return Row.FLATBEDS;
        }
        if (ShellPool.poolOf(variant.id()) == ShellPool.FLATBED) return Row.FLATBEDS;
        if (games.brennan.dungeontrain.portal.PortalCarriageBuilder.isPortalVariant(variant)) return Row.PORTALS;
        return Row.of(ShellPool.of(CarriagePlacer.sizeOf(variant)));
    }

    /** The pure layout: {@code ids} in registry order, each laid in its row after the ones before. */
    static Rows rows(String[] ids, Function<String, Row> rowOf, ToIntFunction<String> lengthOf, int firstX) {
        Map<String, Integer> startX = new HashMap<>(ids.length * 2);
        Map<String, Row> rows = new HashMap<>(ids.length * 2);
        Map<Row, Integer> next = new EnumMap<>(Row.class);
        for (Row r : Row.values()) next.put(r, firstX);
        for (String id : ids) {
            if (startX.containsKey(id)) continue;
            Row row = rowOf.apply(id);
            int x = next.get(row);
            startX.put(id, x);
            rows.put(id, row);
            next.put(row, x + lengthOf.applyAsInt(id) + EditorLayout.GAP);
        }
        return new Rows(Map.copyOf(startX), Map.copyOf(rows), Map.copyOf(next));
    }

    // ---- memoised for the live registry --------------------------------------------------------

    private static List<CarriageVariant> source;
    private static int sizesVersion = -1;
    private static int poolsVersion = -1;
    private static CarriageDims sourceDims;
    private static int sourceGroupSize;
    private static Rows current;

    /** The rows for the registry as it stands, rebuilt when a template, size or pool changes. */
    public static synchronized Rows current(CarriageDims dims, int groupSize) {
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        int sizes = TemplateSizeStore.SHELLS.version();
        int pools = ShellPool.version();
        if (current != null && all == source && sizes == sizesVersion && pools == poolsVersion
                && dims.equals(sourceDims) && groupSize == sourceGroupSize) {
            return current;
        }
        Map<String, CarriageVariant> byId = new HashMap<>(all.size() * 2);
        String[] ids = new String[all.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = all.get(i).id();
            byId.putIfAbsent(ids[i], all.get(i));
        }
        current = rows(ids, id -> rowOf(byId.get(id)),
            id -> CarriageEditor.plotDims(byId.get(id), dims).length(), 0);
        source = all;
        sizesVersion = sizes;
        poolsVersion = pools;
        sourceDims = dims;
        sourceGroupSize = groupSize;
        return current;
    }

    /** {@code variant}'s plot origin, or null when it is not registered. */
    public static BlockPos originOf(CarriageVariant variant, CarriageDims dims, int groupSize) {
        Rows rows = current(dims, groupSize);
        Integer x = rows.startX().get(variant.id());
        if (x == null) return null;
        return new BlockPos(x, EditorLayout.PLOT_Y, rowZ(rows.rowOf().get(variant.id()), dims.width()));
    }

    /** The variants standing in {@code row}, in row order. */
    public static List<CarriageVariant> membersOf(Row row) {
        List<CarriageVariant> out = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (rowOf(v) == row) out.add(v);
        }
        return out;
    }
}
