package org.digit.notify.provider.template;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.Recipient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateProviderTest {

    @Test
    void providerName_returnsTemplate() {
        var provider = new TemplateProvider();
        assertThat(provider.providerName()).isEqualTo("template");
    }

    @Test
    void supportedChannel_returnsSms() {
        var provider = new TemplateProvider();
        assertThat(provider.supportedChannel()).isEqualTo(Channel.SMS);
    }

    @Test
    void send_throwsUnsupportedOperationException_documentingTodoForImplementors() {
        var provider = new TemplateProvider();
        var message = new ChannelMessage(
            Channel.SMS, "Test body", null, null, Map.of());
        var recipient = new Recipient(
            "+1234567890", null, List.of(), "IN", Map.of());
        assertThatThrownBy(() -> provider.send(message, recipient, Map.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
