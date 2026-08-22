import { describe, expect, it } from "vitest";

import {
  researchRefreshJobPageSchema,
  researchRefreshJobSchema,
} from "@/shared/api/jobs-schemas";

const job = {
  id: "11111111-1111-4111-8111-111111111111",
  jobType: "SEARCH_METADATA" as const,
  targetId: "22222222-2222-4222-8222-222222222222",
  trigger: "MANUAL" as const,
  status: "FAILED" as const,
  attemptCount: 3,
  maxAttempts: 3,
  availableAt: "2026-08-21T10:00:00Z",
  leasedUntil: null,
  lastErrorCode: "SEARCH_PROVIDER_UNAVAILABLE",
  lastErrorDetail: "Research providers could not complete the metadata refresh.",
  createdAt: "2026-08-21T09:00:00Z",
  startedAt: "2026-08-21T09:01:00Z",
  completedAt: "2026-08-21T10:00:00Z",
  updatedAt: "2026-08-21T10:00:00Z",
};

describe("refresh-job schemas", () => {
  it("accepts a durable failed job and its page envelope", () => {
    expect(researchRefreshJobSchema.safeParse(job).success).toBe(true);
    expect(
      researchRefreshJobPageSchema.safeParse({
        items: [job],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }).success,
    ).toBe(true);
  });

  it("rejects inconsistent errors and attempt counts", () => {
    expect(
      researchRefreshJobSchema.safeParse({
        ...job,
        attemptCount: 4,
        lastErrorDetail: null,
      }).success,
    ).toBe(false);
  });
});
