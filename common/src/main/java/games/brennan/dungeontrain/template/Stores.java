package games.brennan.dungeontrain.template;

import net.minecraft.server.level.ServerPlayer;

/**
 * Type-erasing bridge between {@link Template} and {@link TemplateStore}.
 *
 * <p>{@link Template#store()} returns {@code TemplateStore<? extends Template>}
 * because each record permittee binds its own type parameter. Calling
 * {@code store().save(player, this)} from a method that holds a wildcard
 * {@code Template} requires an unchecked cast — this helper keeps every
 * such cast in one place so {@code SaveCommand} and friends don't have
 * to suppress warnings inline.</p>
 *
 * <p>Phase-2 dispatch sites should call these helpers instead of
 * resolving {@code template.store()} themselves.</p>
 */
public final class Stores {

    private Stores() {}

    /**
     * Save the player's current plot for {@code template} via its store.
     * The unchecked cast is sound because each record's overridden
     * {@code store()} returns a {@code TemplateStore} bound to that
     * record's type — passing {@code template} back into it is always
     * correctly-typed at runtime.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static SaveResult save(ServerPlayer player, Template template) throws Exception {
        return ((TemplateStore) template.store()).save(player, template);
    }

    /** Promotion eligibility — see {@link TemplateStore#canPromote}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean canPromote(Template template) {
        return ((TemplateStore) template.store()).canPromote(template);
    }

    /** Promote the per-install config copy to the source tree — see {@link TemplateStore#promote}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void promote(Template template) throws Exception {
        ((TemplateStore) template.store()).promote(template);
    }
}
