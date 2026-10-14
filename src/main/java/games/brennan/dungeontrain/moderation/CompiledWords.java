package games.brennan.dungeontrain.moderation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A word list compiled for matching: built once whenever the list changes, read lock-free by every
 * check. Four kinds of entry, mirroring the relay's {@code moderation-wordlist.js}:
 *
 * <ul>
 *   <li>{@link #wordTerms} — whole-word only (short or ambiguous terms such as "coon" that hide inside
 *       ordinary words);</li>
 *   <li>{@link #collapseTerms} — distinctive terms also matched on the collapsed text so separators,
 *       repeats and leetspeak can't hide them;</li>
 *   <li>{@link #cjkTerms} — substring-matched on the CJK collapse (no word boundaries in Chinese);</li>
 *   <li>{@link #phrasePatterns} — multi-word phrases, matched on the folded text with boundaries.</li>
 * </ul>
 *
 * <p>{@link #allow} lists whole words that must never be masked even when a collapse hit lands inside
 * them ("nigeria", "scunthorpe"). Every stored term is already folded with {@link TextNormalizer}.</p>
 */
public final class CompiledWords {

    public static final CompiledWords EMPTY = new CompiledWords(List.of(), List.of(), List.of(), List.of(), List.of());

    private final Set<String> wordTerms;
    private final List<String> collapseTerms;
    private final List<String> cjkTerms;
    private final List<Pattern> phrasePatterns;
    private final List<String> phraseTerms;
    private final Set<String> allow;

    public CompiledWords(Collection<String> word, Collection<String> collapse, Collection<String> cjk,
                         Collection<String> phrases, Collection<String> allow) {
        Set<String> w = new HashSet<>();
        for (String t : word) addIfUsable(w, TextNormalizer.normalizeTerm(t));
        // A distinctive term is matched both ways: whole word AND collapsed.
        List<String> c = new ArrayList<>();
        for (String t : collapse) {
            addIfUsable(w, TextNormalizer.normalizeTerm(t));
            String squeezed = TextNormalizer.collapseTerm(t);
            if (squeezed.length() >= 3 && !c.contains(squeezed)) c.add(squeezed);
        }
        List<String> k = new ArrayList<>();
        for (String t : cjk) {
            String n = TextNormalizer.cjkCollapse(TextNormalizer.fold(t == null ? "" : t)).text();
            if (n.length() >= 2 && !k.contains(n)) k.add(n);
        }
        List<Pattern> p = new ArrayList<>();
        List<String> pt = new ArrayList<>();
        for (String t : phrases) {
            String n = TextNormalizer.normalizeTerm(t).replaceAll("\\s+", " ");
            if (n.length() < 3 || pt.contains(n)) continue;
            pt.add(n);
            p.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n).replace(" ", "\\E\\s+\\Q") + "(?![\\p{L}\\p{N}])"));
        }
        Set<String> a = new HashSet<>();
        for (String t : allow) addIfUsable(a, TextNormalizer.normalizeTerm(t));
        this.wordTerms = Set.copyOf(w);
        this.collapseTerms = List.copyOf(c);
        this.cjkTerms = List.copyOf(k);
        this.phrasePatterns = List.copyOf(p);
        this.phraseTerms = List.copyOf(pt);
        this.allow = Set.copyOf(a);
    }

    private static void addIfUsable(Set<String> into, String folded) {
        if (folded.length() >= 2 && folded.length() <= 64 && !folded.contains(" ")) into.add(folded);
    }

    public boolean isEmpty() {
        return wordTerms.isEmpty() && collapseTerms.isEmpty() && cjkTerms.isEmpty() && phrasePatterns.isEmpty();
    }

    public boolean isWord(String folded) {
        return wordTerms.contains(folded);
    }

    public boolean isAllowed(String folded) {
        return allow.contains(folded);
    }

    public List<String> collapseTerms() {
        return collapseTerms;
    }

    public List<String> cjkTerms() {
        return cjkTerms;
    }

    public List<Pattern> phrasePatterns() {
        return phrasePatterns;
    }

    public List<String> phraseTerms() {
        return phraseTerms;
    }

    /** How many distinct terms this list matches on (for the log line). */
    public int termCount() {
        return wordTerms.size() + collapseTerms.size() + cjkTerms.size() + phrasePatterns.size();
    }
}
