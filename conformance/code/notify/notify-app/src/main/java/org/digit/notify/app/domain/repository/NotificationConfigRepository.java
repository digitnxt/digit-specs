package org.digit.notify.app.domain.repository;

import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationConfigRepository extends JpaRepository<NotificationConfigEntity, UUID> {

    Optional<NotificationConfigEntity> findByTenantIdAndTemplateCodeAndIsActiveTrue(
        String tenantId, String templateCode);

    List<NotificationConfigEntity> findByTenantId(String tenantId);

    List<NotificationConfigEntity> findByTenantIdAndIsActive(String tenantId, boolean isActive);

    Optional<NotificationConfigEntity> findByTenantIdAndTemplateCode(String tenantId, String templateCode);

    /**
     * One query for every combination of the list filters, so they compose. Derived finders would
     * need a method per combination, and the chain of them this replaced applied only the first
     * filter supplied and silently ignored the rest.
     *
     * <p>A null parameter drops its condition. tag and templateCode match exactly.
     */
    @Query("""
        SELECT c FROM NotificationConfigEntity c
        WHERE c.tenantId = :tenantId
          AND (:templateCode IS NULL OR c.templateCode = :templateCode)
          AND (:tag IS NULL OR c.tag = :tag)
          AND (:isActive IS NULL OR c.isActive = :isActive)
        ORDER BY c.templateCode
        """)
    List<NotificationConfigEntity> search(
        @Param("tenantId") String tenantId,
        @Param("templateCode") @Nullable String templateCode,
        @Param("tag") @Nullable String tag,
        @Param("isActive") @Nullable Boolean isActive);
}
