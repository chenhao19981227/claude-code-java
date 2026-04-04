package com.claude.code.api;

import java.util.Map;

public interface StreamListener {
    void onMessageStart(Map<String, Object> data);
    void onContentBlockStart(Map<String, Object> data);
    void onContentBlockDelta(Map<String, Object> data);
    void onContentBlockStop(Map<String, Object> data);
    void onMessageDelta(Map<String, Object> data);
    void onMessageStop(Map<String, Object> data);
    void onReasoningDelta(String text);
    void onError(Throwable error);
    void onComplete();
}
