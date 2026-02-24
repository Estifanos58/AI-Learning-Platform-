package com.aiplatform.gateway.config;

import com.aiplatform.auth.proto.AuthServiceGrpc;
import io.grpc.ManagedChannel;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @GrpcClient("auth-service")
    private ManagedChannel authManagedChannel;

    @Bean
    public AuthServiceGrpc.AuthServiceBlockingStub authBlockingStub() {
        return AuthServiceGrpc.newBlockingStub(authManagedChannel);
    }
}
