package org.digit.notify.app.domain.repository;

import org.digit.notify.app.domain.entity.ProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<ProviderEntity, UUID> {

    Optional<ProviderEntity> findByProviderName(String providerName);

    List<ProviderEntity> findByIsActive(boolean isActive);

    @Query(value = "SELECT * FROM provider WHERE channels @> jsonb_build_array(:channel)", nativeQuery = true)
    List<ProviderEntity> findByChannel(@Param("channel") String channel);

    @Query(value = "SELECT * FROM provider WHERE channels @> jsonb_build_array(:channel) AND is_active = :isActive", nativeQuery = true)
    List<ProviderEntity> findByChannelAndIsActive(@Param("channel") String channel, @Param("isActive") boolean isActive);
}
