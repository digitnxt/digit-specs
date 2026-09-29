package org.digit.notify.app.controller.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.digit.notify.app.controller.dto.ProviderResponseDto;
import org.digit.notify.app.domain.entity.ProviderEntity;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-22T17:41:43+0530",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.2 (Microsoft)"
)
@Component
public class ProviderMapperImpl implements ProviderMapper {

    @Override
    public ProviderResponseDto toDto(ProviderEntity entity) {
        if ( entity == null ) {
            return null;
        }

        boolean isActive = false;
        UUID id = null;
        String providerName = null;
        List<String> channels = null;

        isActive = entity.isActive();
        id = entity.getId();
        providerName = entity.getProviderName();
        List<String> list = entity.getChannels();
        if ( list != null ) {
            channels = new ArrayList<String>( list );
        }

        ProviderResponseDto providerResponseDto = new ProviderResponseDto( id, providerName, channels, isActive );

        return providerResponseDto;
    }
}
