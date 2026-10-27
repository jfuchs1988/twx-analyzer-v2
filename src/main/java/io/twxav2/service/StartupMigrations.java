package io.twxav2.service;

import io.quarkus.runtime.StartupEvent;
import io.twxav2.model.BpmSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * One-time adjustments of the data inventory to evolved schemas.
 * Runs after schema creation (StartupEvent) and is idempotent.
 */
@ApplicationScoped
public class StartupMigrations {

    private static final Logger LOG = Logger.getLogger(StartupMigrations.class);

    @Inject
    SecretsCrypto secrets;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "mariadb")
    String dbKind;

    /**
     * Severity switch (3-level -> 4-level): ERROR->MAJOR, WARNING->MINOR.
     * MariaDB only: the inventory of legacy values is possible there exclusively
     * (ENUM column view via information_schema.column_type, alter table ... enum);
     * DB2 installations and H2 tests come into being only after the switch and
     * partly reject the legacy value literals as a column violation.
     */
    @Transactional
    public void migrateSeverity(@Observes StartupEvent ev, EntityManager em) {
        if (!"mariadb".equalsIgnoreCase(dbKind)) {
            return;
        }
        String colType = (String) em.createNativeQuery(
                "select column_type from information_schema.columns"
                        + " where table_schema = database()"
                        + " and table_name = 'finding' and column_name = 'severity'")
                .getSingleResult();
        if (!colType.contains("ERROR")) {
            return; // already migrated (or fresh installation)
        }
        em.createNativeQuery("alter table finding modify severity"
                + " enum('ERROR','WARNING','INFO','CRITICAL','MAJOR','MINOR')")
                .executeUpdate();
        em.createNativeQuery("update finding set severity = 'MAJOR' where severity = 'ERROR'")
                .executeUpdate();
        em.createNativeQuery("update finding set severity = 'MINOR' where severity = 'WARNING'")
                .executeUpdate();
        em.createNativeQuery("alter table finding modify severity"
                + " enum('CRITICAL','MAJOR','MINOR','INFO')")
                .executeUpdate();
    }

    /**
     * Plaintext password -> v1-encrypted as soon as twxav2.security.db-secret
     * is set (SecretsCrypto). Without a secret everything stays as before.
     */
    @Transactional
    public void encryptBpmPassword(@Observes StartupEvent ev) {
        if (!secrets.enabled()) {
            return;
        }
        BpmSettings s = BpmSettings.findById(1L);
        if (s != null && s.password != null && !s.password.isEmpty()
                && !SecretsCrypto.isEncrypted(s.password)) {
            s.password = secrets.encrypt(s.password);
            s.persist();
            LOG.info("Encrypted the BAW password in the database (DB_SECRET is configured).");
        }
    }
}
