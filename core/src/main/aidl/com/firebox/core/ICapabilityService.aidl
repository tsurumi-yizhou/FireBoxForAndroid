package com.firebox.core;

import com.firebox.core.ChatCompletionRequest;
import com.firebox.core.ChatCompletionResult;
import com.firebox.core.EmbeddingRequest;
import com.firebox.core.EmbeddingResult;
import com.firebox.core.FunctionCallRequest;
import com.firebox.core.FunctionCallResult;
import com.firebox.core.IChatStreamSink;
import com.firebox.core.ModelInfo;

interface ICapabilityService {
    String Ping(in String message);

    List<ModelInfo> ListModels();

    ChatCompletionResult ChatCompletion(in ChatCompletionRequest req);

    long StartChatCompletionStream(in ChatCompletionRequest req, in IChatStreamSink sink);

    void CancelChatCompletion(long requestId);

    EmbeddingResult CreateEmbeddings(in EmbeddingRequest req);

    FunctionCallResult CallFunction(in FunctionCallRequest req);
}
