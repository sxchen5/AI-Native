package com.example.doubaoai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关入口：统一对外暴露端口，转发到 chat-service，并处理跨域。
 */
@SpringBootApplication
public class ChatGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatGatewayApplication.class, args);
    }
}
