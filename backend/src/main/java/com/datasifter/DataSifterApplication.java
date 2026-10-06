package com.datasifter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataSifterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataSifterApplication.class, args);
    }
}
