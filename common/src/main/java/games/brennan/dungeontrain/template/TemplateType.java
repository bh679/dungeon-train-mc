package games.brennan.dungeontrain.template;

/**
 * Sub-type within a {@link TemplateKind} — distinguishes shells of a single
 * subsystem. Implemented by every type-discriminator enum in the codebase so
 * that {@link Template#type()} can return a uniform handle regardless of
 * which kind owns the type.
 *
 * <p>Examples by kind:
 * <ul>
 *   <li>{@link TemplateKind#CARRIAGE} — {@code CarriagePlacer.CarriageType}
 *       (STANDARD, WINDOWED, FLATBED) — only present on built-in carriages.</li>
 *   <li>{@link TemplateKind#CONTENTS} — {@code CarriageContents.ContentsType}
 *       (DEFAULT) — only present on built-in contents.</li>
 *   <li>{@link TemplateKind#PART} — {@code CarriagePartKind} (FLOOR, WALLS,
 *       ROOF, DOORS) — discriminates regions of the carriage shell.</li>
 *   <li>{@link TemplateKind#PILLAR} — {@code PillarSection} (TOP, MIDDLE,
 *       BOTTOM) — stacking position of the pillar segment.</li>
 *   <li>{@link TemplateKind#STAIRS} — {@code PillarAdjunct} (STAIRS) — the
 *       only adjunct kind today.</li>
 *   <li>{@link TemplateKind#TUNNEL} — {@code TunnelPlacer.TunnelVariant}
 *       (SECTION, PORTAL) — straight section vs entrance facade.</li>
 * </ul>
 *
 * <p>{@link TemplateKind#TRACK} has no sub-type today and reports
 * {@code Optional.empty()} from {@link Template#type()}.</p>
 */
public interface TemplateType {

    /** Stable lower-case identifier — usually the lowercased enum name. */
    String id();

    /** The owning {@link TemplateKind} that this type discriminates. */
    TemplateKind kind();
}
