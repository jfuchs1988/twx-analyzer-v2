package io.twxav2.analysis.ref;

import io.twxav2.analysis.TwxRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CDI producer supplies exactly one TwxRule bean per catalog entry (R-APP-001..R-QA-009).
 * All 156 producer methods are traversed reflectively — this keeps the file
 * small while every method body still executes.
 */
class RefRulesProducerTest {

    private static List<TwxRule> allBeans() throws Exception {
        RefRulesProducer producer = new RefRulesProducer();
        List<TwxRule> beans = new ArrayList<>();
        for (int i = 0; i < RefRuleCatalog.SIZE; i++) {
            String method = "rule" + RefRuleCatalog.byIndex(i).id.replace("-", "_");
            Object out = RefRulesProducer.class.getDeclaredMethod(method).invoke(producer);
            assertInstanceOf(TwxRule.class, out, method);
            beans.add((TwxRule) out);
        }
        return beans;
    }

    @Test
    void erzeugtGenauEineBeanJeKatalogeintrag() throws Exception {
        List<TwxRule> beans = allBeans();
        assertEquals(RefRuleCatalog.SIZE, beans.size());
        for (int i = 0; i < beans.size(); i++) {
            assertEquals(RefRuleCatalog.byIndex(i).id, beans.get(i).id(),
                    "Producer-Methode " + i);
        }
    }

    @Test
    void idsEindeutigFormatUndBerichtreihenfolge() throws Exception {
        List<String> ids = allBeans().stream().map(TwxRule::id).toList();
        assertEquals(RefRuleCatalog.SIZE, ids.stream().distinct().count());
        assertEquals("R-APP-001", ids.get(0));
        assertEquals("R-QA-009", ids.get(ids.size() - 1));
        for (String id : ids) {
            assertTrue(id.matches("R-(APP|DEP|SVC|BPD|JS|UI|BO|QA)-\\d{3}"), id);
        }
    }

    @Test
    void metadatenAusDerUmschlossenenRegel() throws Exception {
        for (TwxRule bean : allBeans()) {
            assertInstanceOf(CatalogRule.class, bean);
            CatalogRule cr = (CatalogRule) bean;
            assertNotNull(cr.ref(), bean.id());
            assertFalse(bean.title().isEmpty(), bean.id() + " Titel");
            assertFalse(bean.category().isEmpty(), bean.id() + " Kategorie");
            assertTrue(bean.impact() >= 1 && bean.impact() <= 3, bean.id() + " Impact");
            assertTrue(bean.cap() >= 1, bean.id() + " Cap");
        }
    }
}
