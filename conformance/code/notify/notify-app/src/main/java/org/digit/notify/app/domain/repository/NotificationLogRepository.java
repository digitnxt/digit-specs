package org.digit.notify.app.domain.repository;

import org.digit.notify.app.domain.entity.NotificationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, UUID> {

    /** Scoped for the same reason as the attempt lookup: a notification id alone names no tenant. */
    Optional<NotificationLogEntity> findByTenantIdAndNotificationId(String tenantId, String notificationId);
}
