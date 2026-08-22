import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RefreshJobDashboard } from "@/features/jobs/refresh-job-dashboard";
import type {
  ResearchRefreshJob,
  ResearchRefreshJobPage,
} from "@/shared/api/jobs-schemas";

const failedJob: ResearchRefreshJob = {
  id: "11111111-1111-4111-8111-111111111111",
  jobType: "SEARCH_METADATA",
  targetId: "22222222-2222-4222-8222-222222222222",
  trigger: "MANUAL",
  status: "FAILED",
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

function page(items: ResearchRefreshJob[]): ResearchRefreshJobPage {
  return {
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : 1,
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("RefreshJobDashboard", () => {
  it("renders a durable failure and replaces it with the validated retry response", async () => {
    const user = userEvent.setup();
    const retried: ResearchRefreshJob = {
      ...failedJob,
      trigger: "RETRY",
      status: "QUEUED",
      attemptCount: 0,
      lastErrorCode: null,
      lastErrorDetail: null,
      startedAt: null,
      completedAt: null,
      updatedAt: "2026-08-21T10:01:00Z",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(retried), {
        status: 202,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    render(<RefreshJobDashboard initialPage={page([failedJob])} />);

    expect(screen.getByText("SEARCH_PROVIDER_UNAVAILABLE")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/refresh-jobs/${failedJob.id}/retry`,
      { method: "POST" },
    );
    expect(await screen.findByText("The failed refresh was queued again."))
      .toBeInTheDocument();
    expect(screen.getByText("Queued")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("shows an explicit empty state", () => {
    render(<RefreshJobDashboard initialPage={page([])} />);

    expect(screen.getByRole("heading", { name: "No refresh jobs yet" }))
      .toBeInTheDocument();
  });
});
