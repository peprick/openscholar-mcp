import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PaperPage from "@/app/papers/[paperId]/page";
import {
  paperAccessResponseFixture,
  paperDetailsResponseFixture,
  relatedPapersResponseFixture,
  testIds,
} from "@/test/fixtures";

const backend = vi.hoisted(() => {
  class BackendApiError extends Error {
    constructor(readonly status: number) {
      super(`Backend API error ${status}`);
    }
  }

  return {
    BackendApiError,
    getPaperAccess: vi.fn(),
    getPaperDetails: vi.fn(),
    getRelatedPapers: vi.fn(),
  };
});

const pageBoundary = vi.hoisted(() => ({
  notFound: vi.fn((): never => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));

vi.mock("@/shared/api/server", () => backend);
vi.mock("next/navigation", () => ({ notFound: pageBoundary.notFound }));
vi.mock("@/features/access/access-panel", () => ({
  AccessPanel: () => <div data-testid="access-panel" />,
}));
vi.mock("@/features/citations/citation-actions", () => ({
  CitationActions: () => <div data-testid="citation-actions" />,
}));
vi.mock("@/features/library/save-paper-panel", () => ({
  SavePaperPanel: () => <div data-testid="save-paper-panel" />,
}));

beforeEach(() => {
  vi.clearAllMocks();
  backend.getPaperDetails.mockResolvedValue(paperDetailsResponseFixture());
  backend.getPaperAccess.mockResolvedValue(paperAccessResponseFixture());
  backend.getRelatedPapers.mockResolvedValue(relatedPapersResponseFixture());
});

afterEach(cleanup);

describe("PaperPage", () => {
  it("loads and renders a concise set of local related papers", async () => {
    render(
      await PaperPage({ params: Promise.resolve({ paperId: testIds.paper }) }),
    );

    expect(backend.getRelatedPapers).toHaveBeenCalledWith(testIds.paper, 5);
    expect(
      screen.getByRole("link", {
        name: "Message passing networks for molecular discovery",
      }),
    ).toHaveAttribute("href", `/papers/${testIds.relatedPaper}`);
    expect(screen.getByRole("heading", { name: "Sources" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Paper details" })).toBeVisible();
    expect(screen.queryByText("Metadata completeness")).not.toBeInTheDocument();
    expect(screen.queryByText("Citation count as of")).not.toBeInTheDocument();
    expect(screen.queryByText("Metadata updated")).not.toBeInTheDocument();
  });

  it("rejects malformed paper IDs before any backend request", async () => {
    await expect(
      PaperPage({ params: Promise.resolve({ paperId: "not-a-uuid" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");

    expect(backend.getPaperDetails).not.toHaveBeenCalled();
    expect(backend.getPaperAccess).not.toHaveBeenCalled();
    expect(backend.getRelatedPapers).not.toHaveBeenCalled();
  });

  it("keeps paper details available when related discovery fails", async () => {
    backend.getRelatedPapers.mockRejectedValue(
      new backend.BackendApiError(503),
    );

    render(
      await PaperPage({ params: Promise.resolve({ paperId: testIds.paper }) }),
    );

    expect(
      screen.getByRole("heading", {
        name: "Graph neural networks for molecular property prediction",
      }),
    ).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Related papers are temporarily unavailable.",
    );
  });

  it("still maps a canonical paper-details 404 to the not-found boundary", async () => {
    backend.getPaperDetails.mockRejectedValue(new backend.BackendApiError(404));

    await expect(
      PaperPage({ params: Promise.resolve({ paperId: testIds.paper }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });
});
