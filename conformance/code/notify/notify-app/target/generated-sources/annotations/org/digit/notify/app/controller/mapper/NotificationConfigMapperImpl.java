package org.digit.notify.app.controller.mapper;

import java.util.UUID;
import javax.annotation.processing.Generated;
import org.digit.notify.app.controller.dto.NotificationConfigRequestDto;
import org.digit.notify.app.controller.dto.NotificationConfigResponseDto;
import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.config.ChannelsConfig;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-22T17:41:43+0530",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.2 (Microsoft)"
)
@Component
public class NotificationConfigMapperImpl implements NotificationConfigMapper {

    @Override
    public NotificationConfigEntity toEntity(NotificationConfigRequestDto dto) {
        if ( dto == null ) {
            return null;
        }

        NotificationConfigEntity notificationConfigEntity = new NotificationConfigEntity();

        notificationConfigEntity.setTemplateCode( dto.templateCode() );
        notificationConfigEntity.setTag( dto.tag() );
        notificationConfigEntity.setChannels( dto.channels() );

        notificationConfigEntity.setActive( true );

        return notificationConfigEntity;
    }

    @Override
    public NotificationConfigResponseDto toDto(NotificationConfigEntity entity) {
        if ( entity == null ) {
            return null;
        }

        boolean isActive = false;
        UUID id = null;
        String tenantId = null;
        String templateCode = null;
        ChannelsConfig channels = null;
        String tag = null;

        isActive = entity.isActive();
        id = entity.getId();
        tenantId = entity.getTenantId();
        templateCode = entity.getTemplateCode();
        channels = entity.getChannels();
        tag = entity.getTag();

        NotificationConfigResponseDto notificationConfigResponseDto = new NotificationConfigResponseDto( id, tenantId, templateCode, isActive, channels, tag );

        return notificationConfigResponseDto;
    }
}
