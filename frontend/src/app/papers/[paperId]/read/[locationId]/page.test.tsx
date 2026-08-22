import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import ReaderPage from "@/app/papers/[paperId]/read/[locationId]/page";
import type { ReaderSource } from "@/features/reader/reader-source";
import {
  paperAccessLocationFixture,
  paperAccessResponseFixture,
  paperDetailsResponseFixture,
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
  };
});

const pageBoundary = vi.hoisted(() => ({
  notFound: vi.fn((): never => {
    throw new Error("NEXT_NOT_FOUND");
  }),
  renderReader: vi.fn(),
}));

vi.mock("@/shared/api/server", () => backend);
vi.mock("next/navigation", () => ({ notFound: pageBoundary.notFound }));
vi.mock("@/features/reader/pdf-reader", () => ({
  PdfReader: (props: { source: ReaderSource; title: string }) => {
    pageBoundary.renderReader(props);
    return <div data-testid="pdf-reader">{props.title}</div>;
  },
}));

function pageParams(
  paperId: string = testIds.paper,
  locationId: string = testIds.location,
) {
  return { params: Promise.resolve({ locationId, paperId }) };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-08-18T12:00:00Z"));
  backend.getPaperDetails.mockResolvedValue(paperDetailsResponseFixture());
  backend.getPaperAccess.mockResolvedValue(
    paperAccessResponseFixture({ freshUntil: "2026-08-19T12:00:00Z" }),
  );
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("ReaderPage", () => {
  it("renders only the exact fresh verified reader source", async () => {
    render(await ReaderPage(pageParams()));

    expect(screen.getByTestId("pdf-reader")).toHaveTextContent(
      "Graph neural networks for molecular property prediction",
    );
    expect(pageBoundary.renderReader).toHaveBeenCalledWith({
      source: expect.objectContaining({
        locationId: testIds.location,
        paperId: testIds.paper,
        pdfUrl: "https://repository.example.edu/items/paper-42.pdf",
      }),
      title: "Graph neural networks for molecular property prediction",
    });
    expect(backend.getPaperDetails).toHaveBeenCalledWith(testIds.paper);
    expect(backend.getPaperAccess).toHaveBeenCalledWith(testIds.paper);
  });

  it("shows only refresh and external fallback actions after access expires", async () => {
    backend.getPaperAccess.mockResolvedValue(
      paperAccessResponseFixture({ freshUntil: "2026-08-18T11:59:59Z" }),
    );

    render(await ReaderPage(pageParams()));

    expect(
      screen.getByRole("heading", { name: "This reader link has expired." }),
    ).toBeVisible();
    expect(screen.queryByTestId("pdf-reader")).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Check access on paper page" }),
    ).toHaveAttribute("href", `/papers/${testIds.paper}`);
    const externalFallback = screen.getByRole("link", {
      name: /Open PDF in a new tab/,
    });
    expect(externalFallback).toHaveAttribute(
      "href",
      "https://repository.example.edu/items/paper-42.pdf",
    );
    expect(externalFallback).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("returns not found for an unverified requested location", async () => {
    backend.getPaperAccess.mockResolvedValue(
      paperAccessResponseFixture({
        locations: [
          paperAccessLocationFixture({
            verificationStatus: "UNVERIFIED",
            verifiedAt: null,
          }),
        ],
      }),
    );

    await expect(ReaderPage(pageParams())).rejects.toThrow("NEXT_NOT_FOUND");
    expect(pageBoundary.renderReader).not.toHaveBeenCalled();
  });

  it("rejects malformed route IDs before calling the backend", async () => {
    await expect(
      ReaderPage(pageParams("not-a-paper-id")),
    ).rejects.toThrow("NEXT_NOT_FOUND");

    expect(backend.getPaperDetails).not.toHaveBeenCalled();
    expect(backend.getPaperAccess).not.toHaveBeenCalled();
  });

  it("maps a backend paper-not-found response to the not-found boundary", async () => {
    backend.getPaperAccess.mockRejectedValue(new backend.BackendApiError(404));

    await expect(ReaderPage(pageParams())).rejects.toThrow("NEXT_NOT_FOUND");
    expect(pageBoundary.renderReader).not.toHaveBeenCalled();
  });
});
