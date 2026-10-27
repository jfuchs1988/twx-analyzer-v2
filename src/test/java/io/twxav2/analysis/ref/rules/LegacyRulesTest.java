package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyRulesTest {

    private static List<Finding> runCoachRule(String script) {
        TwxPackage app = new TwxPackage();
        app.id = "app";
        app.name = "Synthetic";
        app.acronym = "SYN";
        TwxModel model = new TwxModel();
        model.app = app;
        TwxObject view = new TwxObject("64.11111111-1111-4111-8111-111111111111", "Test View",
                "coachView", "v", "<teamworks><coachView><inlineScript name=\"inline\">"
                + "<scriptType>JS</scriptType><scriptBlock>" + script
                + "</scriptBlock></inlineScript></coachView></teamworks>", app);
        app.objects.put(view.id, view);
        Rule rule = LegacyRules.rules().stream().filter(r -> r.id.equals("R-UI-018")).findFirst().orElseThrow();
        List<Finding> findings = new ArrayList<>();
        rule.check(new RuleContext(model, Map.of(), false), findings);
        return findings;
    }

    @Test
    void bindingLiteralIsCheckedButCommentsAreIgnored() {
        assertEquals(1, runCoachRule("context.binding.get(\"value\").name;").size());
        assertEquals(0, runCoachRule("// context.binding.get(\"value\").name;\nvar safe = 1;").size());
    }
}
