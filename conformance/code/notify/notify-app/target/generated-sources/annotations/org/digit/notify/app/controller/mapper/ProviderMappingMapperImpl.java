package org.digit.notify.app.controller.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.digit.notify.app.controller.dto.ProviderMappingRequestDto;
import org.digit.notify.app.controller.dto.ProviderMappingResponseDto;
import org.digit.notify.app.domain.entity.ProviderMappingEntity;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-22T17:41:43+0530",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.2 (Microsoft)"
)
@Component
public class ProviderMappingMapperImpl implements ProviderMappingMapper {

    @Override
    public ProviderMappingEntity toEntity(ProviderMappingRequestDto dto) {
        if ( dto == null ) {
            return null;
        }

        ProviderMappingEntity providerMappingEntity = new ProviderMappingEntity();

        providerMappingEntity.setChannel( dto.channel() );
        providerMappingEntity.setCountry( dto.country() );
        List<String> list = dto.providers();
        if ( list != null ) {
            providerMappingEntity.setProviders( new ArrayList<String>( list ) );
        }

        return providerMappingEntity;
    }

    @Override
    public ProviderMappingResponseDto toDto(ProviderMappingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        UUID id = null;
        String tenantId = null;
        String channel = null;
        String country = null;
        List<String> providers = null;

        id = entity.getId();
        tenantId = entity.getTenantId();
        channel = entity.getChannel();
        country = entity.getCountry();
        List<String> list = entity.getProviders();
        if ( list != null ) {
            providers = new ArrayList<String>( list );
        }

        ProviderMappingResponseDto providerMappingResponseDto = new ProviderMappingResponseDto( id, tenantId, channel, country, providers );

        return providerMappingResponseDto;
    }
}
