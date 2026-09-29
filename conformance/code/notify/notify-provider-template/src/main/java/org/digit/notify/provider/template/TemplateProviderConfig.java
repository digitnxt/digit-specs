package org.digit.notify.provider.template;

public class TemplateProviderConfig {

    private final String apiKey;
    private final String fromAddress;

    public TemplateProviderConfig() {
        this.apiKey = System.getenv("NOTIFY_PROVIDER_TEMPLATE_API_KEY");
        this.fromAddress = System.getenv("NOTIFY_PROVIDER_TEMPLATE_FROM_ADDRESS");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "NOTIFY_PROVIDER_TEMPLATE_API_KEY environment variable is required");
        }
    }

    public String getApiKey() { return apiKey; }
    public String getFromAddress() { return fromAddress; }
}
