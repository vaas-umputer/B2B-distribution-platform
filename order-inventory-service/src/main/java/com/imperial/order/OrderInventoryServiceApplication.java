package com.imperial.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@ComponentScan(basePackages = "com.imperial")
@EnableFeignClients(basePackages = "com.imperial.order.client")
@EnableTransactionManagement(order = 0)
@EnableMethodSecurity
public class OrderInventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderInventoryServiceApplication.class, args);
    }
}
