package io.twxav2.analysis.ref;

import io.twxav2.analysis.ref.rules.Rule;
import io.twxav2.analysis.ref.rules.RuleSet;

import java.util.List;

/**
  * Catalog of the rule system: the rules carry their final
  * project ID directly in the rule class — format {@code R-<CAT>-<nnn>} (category
 * APP/DEP/SVC/BPD/JS/UI/BO/QA, per category ascending in report order
 * of the rule groups). There is no second numbering level anymore.
 */
public final class RefRuleCatalog {

    /** Expected catalog size; changes only with catalog maintenance. */
    public static final int SIZE = 156;

    private static final List<Rule> ALL = RuleSet.all();

    private RefRuleCatalog() { }

    public static List<Rule> all() {
        return ALL;
    }

    public static Rule byIndex(int index) {
        return ALL.get(index);
    }

    static {
        if (ALL.size() != SIZE) {
            throw new IllegalStateException(
                    "Rule catalog expected " + SIZE + " rules, but found " + ALL.size());
        }
    }
}
