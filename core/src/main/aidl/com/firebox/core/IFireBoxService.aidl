// IFireBoxService.aidl
package com.firebox.core;

import com.firebox.core.VirtualModelInfo;
import com.firebox.core.ChatCompletionRequest;
import com.firebox.core.ChatCompletionResult;
import com.firebox.core.IChatStreamCallback;
import com.firebox.core.EmbeddingRequest;
import com.firebox.core.EmbeddingResult;
import com.firebox.core.FunctionCallRequest;
import com.firebox.core.FunctionCallResult;

// 定义跨进程通信接口
interface IFireBoxService {
    // 执行操作并返回结果
    String performOperation();

    // 获取服务版本
    int getVersionCode();

    // 注册回调
    void registerCallback(in IBinder callback);

    // 注销回调
    void unregisterCallback(in IBinder callback);

    // --- AI 能力 ---

    // 获取可用虚拟模型列表
    List<VirtualModelInfo> listVirtualModels();

    // 同步聊天补全
    ChatCompletionResult chatCompletion(in ChatCompletionRequest req);

    // 异步流式聊天补全，返回 requestId
    long startChatCompletionStream(in ChatCompletionRequest req, in IChatStreamCallback cb);

    // 取消流式聊天
    void cancelChatCompletion(long requestId);

    // 嵌入
    EmbeddingResult createEmbeddings(in EmbeddingRequest req);

    // 结构化函数调用
    FunctionCallResult callFunction(in FunctionCallRequest req);
}
