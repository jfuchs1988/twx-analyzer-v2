package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single row (id=1): BPM connection from the settings UI (ADR-010).
 * The password is only written and never returned via the API;
 * with twxav2.security.db-secret set it is stored encrypted in the
 * local MariaDB (volume data/, not committed, see SecretsCrypto).
 */
@Entity
@Table(name = "bpm_settings")
public class BpmSettings extends PanacheEntityBase {

    @Id
    public Long id = 1L;

    @Column(name = "base_url")
    public String baseUrl;

    @Column(name = "user_name")
    public String username;

    @Column(name = "password", length = 1000)
    public String password;

    /** optional path to a CA bundle (PEM) for the BPM TLS connection (settings UI). */
    @Column(name = "ca_file", length = 500)
    public String caFile;

    public static BpmSettings get() {
        BpmSettings s = findById(1L);
        if (s == null) {
            s = new BpmSettings();
            s.persist();
        }
        return s;
    }
}
