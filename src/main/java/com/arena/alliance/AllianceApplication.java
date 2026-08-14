package com.arena.alliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AllianceApplication {

    public static void main(String[] args) {
        // 默认 SQLite 库文件与密钥文件都放 data 目录，须在数据源初始化前存在
        try {
            Files.createDirectories(Path.of(System.getenv().getOrDefault("ALLIANCE_DATA_DIR", "./data")));
        } catch (Exception ignored) {
        }
        SpringApplication.run(AllianceApplication.class, args);
    }
}
