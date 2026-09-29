package org.digit.notify.app.controller.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.digit.notify.app.controller.dto.ChannelDispatchStatusDto;
import org.digit.notify.app.controller.dto.NotifyRequestDto;
import org.digit.notify.app.controller.dto.NotifyResponseDto;
import org.digit.notify.app.controller.dto.RecipientDto;
import org.digit.notify.app.model.ChannelDispatchStatus;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.app.model.NotifyResponse;
import org.digit.notify.spi.Recipient;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-22T17:41:43+0530",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.2 (Microsoft)"
)
@Component
public class NotifyMapperImpl implements NotifyMapper {

    @Override
    public NotifyRequest toDomain(NotifyRequestDto dto) {
        if ( dto == null ) {
            return null;
        }

        String templateCode = null;
        Recipient recipient = null;
        Map<String, Object> payload = null;
        String locale = null;

        templateCode = dto.templateCode();
        recipient = toDomain( dto.recipient() );
        Map<String, Object> map = dto.payload();
        if ( map != null ) {
            payload = new LinkedHashMap<String, Object>( map );
        }
        locale = dto.locale();

        Map<String, Object> metadata = dto.metadata() != null ? dto.metadata() : new java.util.HashMap<>();

        NotifyRequest notifyRequest = new NotifyRequest( templateCode, recipient, payload, locale, metadata );

        return notifyRequest;
    }

    @Override
    public Recipient toDomain(RecipientDto dto) {
        if ( dto == null ) {
            return null;
        }

        String phone = null;
        String email = null;
        String countryCode = null;

        phone = dto.phone();
        email = dto.email();
        countryCode = dto.countryCode();

        List<String> deviceTokens = dto.deviceTokens() != null ? dto.deviceTokens() : java.util.Collections.emptyList();
        Map<String, Object> metadata = dto.metadata() != null ? dto.metadata() : new java.util.HashMap<>();

        Recipient recipient = new Recipient( phone, email, deviceTokens, countryCode, metadata );

        return recipient;
    }

    @Override
    public NotifyResponseDto toDto(NotifyResponse response) {
        if ( response == null ) {
            return null;
        }

        String notificationId = null;
        String templateCode = null;
        List<ChannelDispatchStatusDto> channels = null;

        notificationId = response.notificationId();
        templateCode = response.templateCode();
        channels = channelDispatchStatusListToChannelDispatchStatusDtoList( response.channels() );

        NotifyResponseDto notifyResponseDto = new NotifyResponseDto( notificationId, templateCode, channels );

        return notifyResponseDto;
    }

    protected ChannelDispatchStatusDto channelDispatchStatusToChannelDispatchStatusDto(ChannelDispatchStatus channelDispatchStatus) {
        if ( channelDispatchStatus == null ) {
            return null;
        }

        String channel = null;
        String status = null;
        String provider = null;
        String reason = null;

        channel = channelDispatchStatus.channel();
        status = channelDispatchStatus.status();
        provider = channelDispatchStatus.provider();
        reason = channelDispatchStatus.reason();

        ChannelDispatchStatusDto channelDispatchStatusDto = new ChannelDispatchStatusDto( channel, status, provider, reason );

        return channelDispatchStatusDto;
    }

    protected List<ChannelDispatchStatusDto> channelDispatchStatusListToChannelDispatchStatusDtoList(List<ChannelDispatchStatus> list) {
        if ( list == null ) {
            return null;
        }

        List<ChannelDispatchStatusDto> list1 = new ArrayList<ChannelDispatchStatusDto>( list.size() );
        for ( ChannelDispatchStatus channelDispatchStatus : list ) {
            list1.add( channelDispatchStatusToChannelDispatchStatusDto( channelDispatchStatus ) );
        }

        return list1;
    }
}
