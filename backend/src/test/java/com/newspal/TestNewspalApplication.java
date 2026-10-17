package com.newspal;

import org.springframework.boot.SpringApplication;

public class TestNewspalApplication {

	public static void main(String[] args) {
		SpringApplication.from(NewspalApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
