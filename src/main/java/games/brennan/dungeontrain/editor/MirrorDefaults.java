package games.brennan.dungeontrain.editor;

/**
 * The editor mirror axes a kind of template starts with when its sidecar carries no
 * {@code "mirror"} field — and therefore the state the writer omits that field for, so a sidecar
 * authored at its kind's defaults re-saves byte-identical.
 *
 * <p>Tunnels default X+Z on (they are symmetric on both horizontals, and every shipped tunnel
 * sidecar without a {@code mirror} field relies on it); every other kind is all-off, mirroring being
 * opt-in per template. Y is off for every kind, and the mirror-variants ("V") opt-in is never a
 * default.</p>
 */
public record MirrorDefaults(boolean x, boolean y, boolean z) {

    /** All axes off — carriages, contents, parts, wholes, track tiles, pillars, adjuncts, builder docs. */
    public static final MirrorDefaults NONE = new MirrorDefaults(false, false, false);

    /** X and Z on — tunnel sections and tunnel portals. */
    public static final MirrorDefaults TUNNEL = new MirrorDefaults(true, false, true);
}
