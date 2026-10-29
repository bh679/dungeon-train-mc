package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.editor.SourceTree;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Builds the {@link TemplateStore} a store exposes through {@code adapter()} from its two moving
 * parts — how to save the player's plot, and whether/how to promote the config copy into the
 * source tree. Replaces the anonymous {@code new TemplateStore<>() { … }} each store used to carry.
 */
public final class TemplateAdapters {

    private TemplateAdapters() {}

    @FunctionalInterface
    public interface Saver<T extends Template> {
        SaveResult save(ServerPlayer player, T template) throws Exception;
    }

    @FunctionalInterface
    public interface Promote<T extends Template> {
        void promote(T template) throws Exception;
    }

    /** The promote half: eligibility plus the copy itself. */
    public interface Promoter<T extends Template> {
        boolean canPromote(T template);

        void promote(T template) throws Exception;
    }

    public static <T extends Template> TemplateStore<T> of(TemplateKind kind, Saver<T> saver, Promoter<T> promoter) {
        return new TemplateStore<>() {
            @Override public TemplateKind kind() { return kind; }
            @Override public SaveResult save(ServerPlayer player, T template) throws Exception { return saver.save(player, template); }
            @Override public boolean canPromote(T template) { return promoter.canPromote(template); }
            @Override public void promote(T template) throws Exception { promoter.promote(template); }
        };
    }

    /** A store whose kind has no bundled tier: {@code canPromote} is false and {@code promote} throws {@code message}. */
    public static <T extends Template> TemplateStore<T> noBundledTier(TemplateKind kind, Saver<T> saver, String message) {
        return of(kind, saver, new Promoter<>() {
            @Override public boolean canPromote(T template) { return false; }
            @Override public void promote(T template) { throw new IllegalStateException(message); }
        });
    }

    /** Promotable whenever the dev checkout's source tree is writable. */
    public static <T extends Template> Promoter<T> whenSourceWritable(Promote<T> promote) {
        return when(t -> SourceTree.available(), promote);
    }

    public static <T extends Template> Promoter<T> when(Predicate<T> canPromote, Promote<T> promote) {
        return new Promoter<>() {
            @Override public boolean canPromote(T template) { return canPromote.test(template); }
            @Override public void promote(T template) throws Exception { promote.promote(template); }
        };
    }
}
