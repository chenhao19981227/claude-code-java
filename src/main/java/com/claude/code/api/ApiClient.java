package com.claude.code.api;

import java.util.List;
import java.util.Map;

public interface ApiClient {

    void streamMessage(StreamRequest request, StreamListener listener);

    Map<String, Object> sendMessageSync(StreamRequest request) throws Exception;

    ApiProvider getProvider();
}
