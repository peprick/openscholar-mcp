package com.openscholar.api.system;

import java.time.Clock;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

	private final Clock clock;

	public SystemStatusController(Clock clock) {
		this.clock = clock;
	}

	@GetMapping("/status")
	public SystemStatusResponse status() {
		return new SystemStatusResponse("openscholar-backend", "UP", Instant.now(clock));
	}
}
