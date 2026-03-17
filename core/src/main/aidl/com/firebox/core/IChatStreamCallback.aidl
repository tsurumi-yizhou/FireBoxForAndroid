// IChatStreamCallback.aidl
package com.firebox.core;

import com.firebox.core.ChatStreamEvent;

oneway interface IChatStreamCallback {
    void onEvent(in ChatStreamEvent event);
}
