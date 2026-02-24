package com.aiplatform.auth.grpc;

import io.grpc.Context;

public final class GrpcContextKeys {

    private GrpcContextKeys() {
    }

    public static final Context.Key<String> CORRELATION_ID = Context.key("correlation-id");
}
