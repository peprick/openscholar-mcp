package com.openscholar.api.system;

import java.time.Instant;

public record SystemStatusResponse(String service, String status, Instant timestamp) {
}
