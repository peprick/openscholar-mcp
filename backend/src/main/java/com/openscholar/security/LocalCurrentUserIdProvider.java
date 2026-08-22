package com.openscholar.security;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "openscholar.security.oidc", name = "enabled",
		havingValue = "false", matchIfMissing = true)
class LocalCurrentUserIdProvider implements CurrentUserIdProvider {

	static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Override
	public UUID currentUserId() {
		return LOCAL_USER_ID;
	}
}
