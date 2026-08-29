package com.openscholar;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String POSTGRES_IMAGE =
			"pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		DockerImageName image = DockerImageName.parse(POSTGRES_IMAGE)
				.asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image);
	}

}
