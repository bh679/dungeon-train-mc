package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorSize;

import java.util.Locale;
import java.util.Optional;

/**
 * How big a carriage box is — shared by shells (carriage templates) and the contents that go inside
 * them, because a shell may only take contents of its own size.
 *
 * <ul>
 *   <li>{@link #ROOM} — one carriage, the world's {@link CarriageDims}. Every template before sizes
 *       existed, and the default for anything with no declared size.</li>
 *   <li>{@link #HALF} — the long portal corridor's box ({@link PortalCorridorSize#corridorDims} at
 *       {@link PortalCorridorKind#LONG}): its own slot plus half the cart beside it, about half a
 *       three-carriage group.</li>
 *   <li>{@link #FULL} — one carriage as long as a whole group, {@code groupSize × length}.</li>
 * </ul>
 *
 * <p><b>Full can be impossible.</b> {@link CarriageDims} tops out at {@link CarriageDims#MAX_LENGTH},
 * and a world with long carriages or big groups outgrows it (4 × 9 = 36). {@link #shellDims} then
 * answers empty and every Full caller treats the pool as off, rather than building a box the dims
 * constructor would throw on.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public enum ContentsSize {
    ROOM("room"),
    HALF("half"),
    FULL("full");

    private final String key;

    ContentsSize(String key) {
        this.key = key;
    }

    /** The lowercase spelling used on disk ({@code sizes.json}) and in commands. */
    public String key() {
        return key;
    }

    /** {@code raw} as a size, case-insensitively; empty for null / unknown. */
    public static Optional<ContentsSize> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (ContentsSize s : values()) {
            if (s.key.equals(k)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * The shell box of this size at the world's carriage {@code dims} and {@code groupSize}, or empty
     * when this size cannot be built here (only ever {@link #FULL}, past {@link CarriageDims#MAX_LENGTH}).
     */
    public Optional<CarriageDims> shellDims(CarriageDims dims, int groupSize) {
        return switch (this) {
            case ROOM -> Optional.of(dims);
            case HALF -> Optional.of(PortalCorridorSize.corridorDims(dims, PortalCorridorKind.LONG));
            case FULL -> {
                long length = (long) Math.max(1, groupSize) * dims.length();
                yield length > CarriageDims.MAX_LENGTH
                    ? Optional.empty()
                    : Optional.of(new CarriageDims((int) length, dims.width(), dims.height()));
            }
        };
    }

    /**
     * {@link #shellDims}, falling back to the plain carriage box when this size cannot be built.
     *
     * <p>For the box-sizing call sites that must answer something: a Full template measured against a
     * carriage box fails its size gate and is skipped, which is the "pool is off" behaviour.</p>
     */
    public CarriageDims boxOrRoom(CarriageDims dims, int groupSize) {
        return shellDims(dims, groupSize).orElse(dims);
    }

    /** True when {@link #shellDims} can build this size in this world. */
    public boolean available(CarriageDims dims, int groupSize) {
        return shellDims(dims, groupSize).isPresent();
    }
}
