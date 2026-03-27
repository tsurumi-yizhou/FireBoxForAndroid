package com.firebox.core;

import com.firebox.core.ChatStreamEvent;

oneway interface IChatStreamSink {
    void OnEvent(in ChatStreamEvent event);
}
