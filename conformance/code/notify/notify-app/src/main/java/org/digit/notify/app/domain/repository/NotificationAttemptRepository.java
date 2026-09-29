package org.digit.notify.app.domain.repository;

import org.digit.notify.app.domain.entity.NotificationAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttemptEntity, UUID> {

    /**
     * Tenant-scoped on purpose. The unscoped {@code findByNotificationId} it replaces returned any
     * tenant's attempts — harmless while nothing called it, and a cross-tenant read the moment
     * something did. There is no unscoped variant so that cannot be the easy option.
     */
    List<NotificationAttemptEntity> findByTenantIdAndNotificationId(String tenantId, String notificationId);
}
