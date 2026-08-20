package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.track.variant.TrackKind;

/**
 * Which stored template the open build <em>is</em>, when it is one.
 *
 * <p>{@link BuilderStructureCells} resolves a structure by reading a template out of a store. When
 * the template it is about to read is the one the player has open, the blocks in front of them are a
 * better answer than the blocks on disk — that is the whole of the instant-update feature, and this
 * is how the resolver is told which read that is.</p>
 *
 * <p><b>Identity, not geometry.</b> Whether the open build's <em>blocks</em> may stand in for that
 * template is a separate question, decided once by {@link BuilderStructureNeeds#openTemplateFor}
 * against what the scene declares; a null from there means "read from the store as usual". Keeping
 * the two apart is what lets the resolver stay a pure function of its inputs, with no opinion about
 * builder modes or sub types.</p>
 */
public sealed interface BuilderOpenTemplate {

    /** True when {@code variantId} names the open carriage. */
    boolean matchesCarriage(String variantId);

    /** True when {@code kind} and {@code name} together name the open track-side template. */
    boolean matchesTrack(TrackKind kind, String name);

    /** The open build is a carriage — the one the world was stamped from. */
    record OpenCarriage(String variantId) implements BuilderOpenTemplate {

        @Override
        public boolean matchesCarriage(String other) {
            return variantId != null && !variantId.isEmpty() && variantId.equals(other);
        }

        @Override
        public boolean matchesTrack(TrackKind kind, String name) {
            return false;
        }
    }

    /**
     * The open build is one track-side template.
     *
     * <p>Both halves have to match: {@code default} exists under all eight track kinds, so a name on
     * its own can never say which store a template belongs to.</p>
     */
    record OpenTrack(TrackKind kind, String name) implements BuilderOpenTemplate {

        @Override
        public boolean matchesCarriage(String variantId) {
            return false;
        }

        @Override
        public boolean matchesTrack(TrackKind otherKind, String otherName) {
            return kind == otherKind && name != null && !name.isEmpty() && name.equals(otherName);
        }
    }
}
