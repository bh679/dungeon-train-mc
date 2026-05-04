/**
 * Unified identity layer for every "saveable, editable, in-game placeable"
 * model in Dungeon Train.
 *
 * <p>Until v0.103.x each subsystem (carriage / contents / part / track /
 * pillar / stairs / tunnel) carried its own ad-hoc identifier shape — some
 * were sealed Builtin/Custom interfaces, some were {@code (kind, name)}
 * tuples, some were just bare strings. {@code SaveCommand} and
 * {@code EditorCategory} dispatched per-kind through ~50-line if/instanceof
 * chains; types lived inconsistently on identifiers vs as arguments vs
 * nested in placement utilities.
 *
 * <p>This package introduces a single sealed {@link
 * games.brennan.dungeontrain.template.Template Template} interface that all
 * seven kinds implement, plus a {@link
 * games.brennan.dungeontrain.template.TemplateType TemplateType} marker
 * implemented by every existing type-discriminator enum
 * ({@code CarriageType}, {@code CarriagePartKind}, {@code PillarSection},
 * {@code PillarAdjunct}, {@code ContentsType}, {@code TunnelVariant}).
 *
 * <p>Phase 1 (this revision) is purely additive — registries, stores, and
 * editor flows still use their existing per-kind APIs. Phases 2–3 collapse
 * those onto generic {@code TemplateRegistry<T>} and {@code TemplateStore<T>}
 * interfaces.
 *
 * <p>Note: this package owns identity and metadata only. Block-placement
 * logic continues to live in the per-subsystem {@code *Template} static
 * helpers ({@code train.CarriageTemplate}, {@code tunnel.TunnelTemplate},
 * etc.) — Phase 3 renames those to {@code *Placer} to free the natural
 * names.
 */
package games.brennan.dungeontrain.template;
