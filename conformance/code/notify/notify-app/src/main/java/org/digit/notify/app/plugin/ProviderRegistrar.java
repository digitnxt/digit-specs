package org.digit.notify.app.plugin;

import org.digit.notify.app.domain.entity.AuditDetail;
import org.digit.notify.app.domain.entity.ProviderEntity;
import org.digit.notify.app.domain.repository.ProviderRepository;
import org.digit.notify.spi.NotificationChannelProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ProviderRegistrar {

    private final ProviderRepository providerRepository;

    public ProviderRegistrar(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Transactional
    public void upsertProvider(NotificationChannelProvider provider) {
        var existing = providerRepository.findByProviderName(provider.providerName());
        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setChannels(List.of(provider.supportedChannel().name()));
            entity.getAuditDetail().setLastModifiedTime(Instant.now());
            providerRepository.save(entity);
        } else {
            var entity = new ProviderEntity();
            entity.setProviderName(provider.providerName());
            entity.setChannels(List.of(provider.supportedChannel().name()));
            entity.setActive(true);
            var audit = new AuditDetail();
            audit.setCreatedTime(Instant.now());
            entity.setAuditDetail(audit);
            providerRepository.save(entity);
        }
    }
}
