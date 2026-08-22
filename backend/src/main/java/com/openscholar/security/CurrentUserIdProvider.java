package com.openscholar.security;

import java.util.UUID;

public interface CurrentUserIdProvider {

	UUID currentUserId();
}
