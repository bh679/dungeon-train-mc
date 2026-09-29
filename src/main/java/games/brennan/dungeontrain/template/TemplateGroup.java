package games.brennan.dungeontrain.template;

import java.util.List;

/**
 * One template group a spawn has committed to — either a named group ({@link TemplateMeta#groups()}
 * lists its {@link #id()}) or {@link #UNGROUPED}, the implicit pool of templates that belong to no
 * group at all.
 *
 * <p>Used today by tunnels: a tunnel rolls one of these once and draws every section and entrance
 * between its two ends from it, so a tunnel is either all one named group or all ungrouped. A
 * template in several groups matches each of them.</p>
 *
 * @param id the group id, or {@code ""} for {@link #UNGROUPED}
 */
public record TemplateGroup(String id) {

    /** The pool of templates in no group. */
    public static final TemplateGroup UNGROUPED = new TemplateGroup("");

    public TemplateGroup {
        id = id == null ? "" : id;
    }

    /** A named group, or {@link #UNGROUPED} for a null / blank id. */
    public static TemplateGroup of(String id) {
        return id == null || id.isBlank() ? UNGROUPED : new TemplateGroup(id);
    }

    public boolean isUngrouped() {
        return id.isEmpty();
    }

    /** True when a template belonging to {@code groups} may be drawn for this group. */
    public boolean matches(List<String> groups) {
        if (isUngrouped()) return groups == null || groups.isEmpty();
        return groups != null && groups.contains(id);
    }

    @Override
    public String toString() {
        return isUngrouped() ? "<ungrouped>" : id;
    }
}
