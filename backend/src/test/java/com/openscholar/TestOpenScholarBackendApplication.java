package com.openscholar;

import org.springframework.boot.SpringApplication;

public class TestOpenScholarBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(OpenScholarBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
