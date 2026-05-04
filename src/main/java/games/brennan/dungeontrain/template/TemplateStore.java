package games.brennan.dungeontrain.template;

import net.minecraft.server.level.ServerPlayer;

/**
 * Persistence handle for a {@link Template} of kind {@code T}. Each Template
 * record exposes its store via {@link Template#store()}; commands like
 * {@code /dt save} dispatch through it without caring which subsystem
 * actually owns the bytes on disk.
 *
 * <p>The store handles the player-facing save flow: capture the plot the
 * player is standing in, persist to {@code config/dungeontrain/...}, and
 * (in dev mode) write through to the source tree so the change ships in
 * the next jar build. Promotion ({@code /dt save default}) copies the
 * config-dir file onto the source tree explicitly — only available when
 * {@link #canPromote} returns true.</p>
 *
 * <p>Implementations are static-singleton adapters over the existing
 * per-kind store classes ({@code CarriageTemplateStore.adapter()},
 * {@code CarriagePartTemplateStore.adapter(kind)}, etc.). Phase 2 of the
 * Template OOP refactor (PR following #148) introduced this interface to
 * collapse the per-kind dispatch in {@code SaveCommand} onto a uniform
 * call.</p>
 */
public interface TemplateStore<T extends Template> {

    /** The {@link TemplateKind} this store handles — same as the templates it accepts. */
    TemplateKind kind();

    /**
     * Save the player's current plot for {@code template}. Always writes the
     * per-install config-dir copy (or throws); also writes the source-tree
     * copy when {@code EditorDevMode} is on. Returns the source-tree write
     * outcome — empty {@link SaveResult#skipped} when devmode is off.
     */
    SaveResult save(ServerPlayer player, T template) throws Exception;

    /**
     * True iff this template has a bundled source-tree slot AND the source
     * tree is currently writable (dev environment, not a packaged jar
     * install). Mirrors the existing {@code SaveCommand} eligibility checks
     * so {@code /dt save default} can decide whether to attempt promotion
     * before trying it.
     */
    boolean canPromote(T template);

    /**
     * Copy the per-install config copy of {@code template} into the source
     * tree so the change ships with the next build. Throws when the source
     * tree is not writable, the config file is missing, or the kind has no
     * bundled tier (e.g. tunnels, contents — those return false from
     * {@link #canPromote}, but a caller bypassing the check still gets a
     * clear failure here).
     */
    void promote(T template) throws Exception;
}
