package com.claude.code.config;

import com.claude.code.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiClientConfig {

    @Bean
    public ApiClient apiClient(AppProperties props) {
        var provider = ApiProvider.fromString(props.getProvider());
        var apiKey = props.getApiKey();
        var model = props.getEffectiveModel();
        var baseUrl = props.getBaseUrl();

        boolean hasCustomBaseUrl = baseUrl != null && !baseUrl.trim().isEmpty();
        String resolvedBaseUrl = hasCustomBaseUrl ? baseUrl : null;

        if (provider == ApiProvider.ANTHROPIC) {
            return new AnthropicClient(apiKey, model, resolvedBaseUrl);
        }
        return new OpenAiCompatibleClient(apiKey, model, provider, resolvedBaseUrl);
    }
}
