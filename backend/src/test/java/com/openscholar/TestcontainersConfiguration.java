package com.openscholar;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		DockerImageName image = DockerImageName.parse("pgvector/pgvector:pg17")
				.asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image);
	}

}
