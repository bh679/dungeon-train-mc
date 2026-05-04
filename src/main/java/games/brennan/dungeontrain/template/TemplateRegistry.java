package games.brennan.dungeontrain.template;

import java.util.List;
import java.util.Optional;

/**
 * Discovery handle for every {@link Template} of a single
 * {@link TemplateKind} (or, for kinds with discriminators like
 * {@code TUNNEL.SECTION} vs {@code TUNNEL.PORTAL}, every template within
 * one discriminator slice).
 *
 * <p>Each Template record exposes its registry via
 * {@link Template#registry()}. Implementations are static-singleton
 * adapters over the existing per-kind registry classes
 * ({@code CarriageVariantRegistry.adapter()},
 * {@code TrackVariantRegistry.adapterForPillar(section)}, etc.) — the
 * adapter wraps each registered identifier into the corresponding
 * Template record so callers don't need to know whether the underlying
 * storage is a {@code CarriageVariant}, a {@code String} name, or a
 * {@code (kind, name)} tuple.</p>
 *
 * <p>{@link #all()} order is registry-defined: built-ins first (in their
 * natural enum order), customs after (alphabetical for most registries,
 * insertion order for parts where the X-slot mapping depends on
 * stability). {@link #builtins()} and {@link #customs()} return the same
 * partition independently for callers that need only one tier (typically
 * the editor menus).</p>
 */
public interface TemplateRegistry<T extends Template> {

    /** The {@link TemplateKind} this registry serves. */
    TemplateKind kind();

    /** Builtins followed by customs, in the registry's natural order. Snapshot — safe to iterate. */
    List<T> all();

    /** Built-in templates only — the ones that ship inside the mod jar. */
    List<T> builtins();

    /** User-authored templates only — discovered from the per-install config dir. */
    List<T> customs();

    /** Look up a template by its {@link Template#id()}. */
    Optional<T> find(String id);

    /** Re-scan the bundled manifest + config dir to rebuild {@link #all()}. */
    void reload();

    /** Drop the in-memory state so the next {@link #all()} call reads fresh. */
    void clear();
}
