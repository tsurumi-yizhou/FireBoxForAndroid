// IServiceCallback.aidl
package com.firebox.core;

// 服务端回调接口
interface IServiceCallback {
    // 当服务端有消息通知客户端时调用
    void onMessage(in String message);
    
    // 当服务连接状态改变时调用
    void onConnectionStateChanged(in boolean connected);
}
