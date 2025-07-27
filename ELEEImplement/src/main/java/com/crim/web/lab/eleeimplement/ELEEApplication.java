package com.crim.web.lab.eleeimplement;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;


@EnableConfigurationProperties
@SpringBootApplication
@EnableScheduling
// @ComponentScan(basePackages = "com.crim.web.lab.eleeimplement.*")
public class ELEEApplication {

    public static ApplicationContext context;

    public static void main(String[] args) {
        context = SpringApplication.run(ELEEApplication.class, args);
    }

    public static void run(){
        context = SpringApplication.run(ELEEApplication.class);
    }

    public static void exit(){
        SpringApplication.exit(context);
    }

}
