package org.digit.notify.app.domain.entity;

import com.digit.tenant.migration.web.TenantContext;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * The only table here that is not tenant data. A row says "this pod has a jar that can send on this
 * channel", which is a property of the deployment, so ProviderRegistrar writes the same rows on
 * every startup no matter how many tenants exist.
 *
 * <p>Hence the explicit schema. With separation on, {@code search_path} is the calling tenant's
 * schema and nothing else — there is no fallback to the shared one — so an unqualified {@code
 * provider} would resolve to a per-tenant copy that the registrar never writes to, and every
 * provider-mapping create would fail validation with "Provider not found in registry". Qualifying it
 * pins all four call sites at once, which binding the context at each of them would not.
 *
 * <p>The per-tenant migration still creates an unused {@code provider} table in each tenant schema,
 * because it runs the same scripts. Left alone deliberately: editing an applied migration changes
 * its checksum and every existing deployment then fails Flyway validation.
 */
@Entity
@Table(name = "provider", schema = TenantContext.PUBLIC_SCHEMA)
public class ProviderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_name", nullable = false, unique = true)
    private String providerName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channels", columnDefinition = "jsonb", nullable = false)
    private List<String> channels;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Embedded
    private AuditDetail auditDetail = new AuditDetail();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
