import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SavePaperPanel } from "@/features/library/save-paper-panel";
import {
  collectionListFixture,
  collectionSummaryFixture,
  savedPaperFixture,
  testIds,
} from "@/test/fixtures";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SavePaperPanel", () => {
  it("loads collections and sends a normalized, idempotent paper upsert", async () => {
    const user = userEvent.setup();
    const saved = savedPaperFixture({
      readingStatus: "READING",
      tags: ["machine learning", "key result"],
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(collectionListFixture()))
      .mockResolvedValueOnce(jsonResponse(saved));
    vi.stubGlobal("fetch", fetchMock);

    render(<SavePaperPanel paperId={testIds.paper} />);
    await user.click(screen.getByRole("button", { name: "Save to collection" }));

    await screen.findByRole("option", { name: "Thesis foundations" });
    await user.selectOptions(screen.getByLabelText("Reading status"), "READING");
    await user.type(
      screen.getByLabelText("Tags (optional)"),
      " Machine   Learning, KEY RESULT ",
    );
    await user.click(screen.getByRole("button", { name: "Save paper" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/collections/${testIds.collection}/papers/${testIds.paper}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          readingStatus: "READING",
          tags: ["machine learning", "key result"],
        }),
      },
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Saved to “Thesis foundations”.",
    );
  });

  it("offers a collection creation path when the library is empty", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(collectionListFixture([]))));
    render(<SavePaperPanel paperId={testIds.paper} />);

    await user.click(screen.getByRole("button", { name: "Save to collection" }));
    expect(
      await screen.findByRole("link", { name: "Open research library" }),
    ).toHaveAttribute("href", "/library");
  });

  it("keeps a load failure distinct from an empty library and retries", async () => {
    const user = userEvent.setup();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(
          {
            title: "Backend unavailable",
            status: 503,
            detail: "The library is temporarily unavailable.",
            code: "BACKEND_UNREACHABLE",
          },
          503,
        ),
      )
      .mockResolvedValueOnce(jsonResponse(collectionListFixture([])));
    vi.stubGlobal("fetch", fetchMock);
    render(<SavePaperPanel paperId={testIds.paper} />);

    await user.click(screen.getByRole("button", { name: "Save to collection" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The library is temporarily unavailable.",
    );
    expect(
      screen.queryByText(/Create your first collection/),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Retry loading collections" }),
    );
    expect(
      await screen.findByRole("link", { name: "Open research library" }),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("loads additional collection pages into the picker", async () => {
    const user = userEvent.setup();
    const secondCollection = collectionSummaryFixture({
      collectionId: "e1033f8f-e14d-413c-8ed8-1abed24bd51a",
      name: "Second collection page",
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(
          collectionListFixture(undefined, {
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 2,
          }),
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse(
          collectionListFixture([secondCollection], {
            page: 1,
            size: 50,
            totalElements: 2,
            totalPages: 2,
          }),
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    render(<SavePaperPanel paperId={testIds.paper} />);

    await user.click(screen.getByRole("button", { name: "Save to collection" }));
    await user.click(
      await screen.findByRole("button", { name: "Load more collections" }),
    );

    expect(
      await screen.findByRole("option", { name: "Second collection page" }),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/collections?page=0&size=50");
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/collections?page=1&size=50");
  });
});
