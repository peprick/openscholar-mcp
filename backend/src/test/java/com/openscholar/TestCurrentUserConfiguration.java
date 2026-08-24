package com.openscholar;

import java.util.UUID;

import com.openscholar.security.CurrentUserIdProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestCurrentUserConfiguration {

	private static final UUID LOCAL_USER_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Bean
	CurrentUserIdProvider testCurrentUserIdProvider() {
		return () -> LOCAL_USER_ID;
	}
}
