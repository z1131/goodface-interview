package com.deepknow.goodface.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
@SpringBootApplication
@MapperScan("com.deepknow.goodface.interview.repo.mapper")
@EnableDubbo
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}