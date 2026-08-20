import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SearchPagination } from "@/features/search/search-pagination";
import { SearchResults } from "@/features/search/search-results";
import { searchResponseFixture, testIds } from "@/test/fixtures";

const navigation = vi.hoisted(() => ({
  push: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

const nextSearchId = "14a97a49-9203-4871-9924-d8bf4b08dcb4";

afterEach(() => {
  cleanup();
  navigation.push.mockReset();
  vi.unstubAllGlobals();
});

describe("SearchPagination", () => {
  it("requests the next immutable page and navigates to its snapshot", async () => {
    const user = userEvent.setup();
    const nextPage = {
      ...searchResponseFixture(),
      searchId: nextSearchId,
      nextCursor: null,
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(nextPage), {
        status: 201,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    render(<SearchPagination searchId={testIds.search} />);
    await user.click(screen.getByRole("button", { name: "Next results" }));

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/searches/${testIds.search}/next`,
      { method: "POST" },
    );
    await waitFor(() =>
      expect(navigation.push).toHaveBeenCalledWith(
        `/searches/${nextSearchId}`,
      ),
    );
  });

  it("surfaces a backend problem without navigating", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: "urn:openscholar:problem:search-page-exhausted",
            title: "Search page exhausted",
            status: 409,
            detail: "This search snapshot has no additional provider page.",
            code: "SEARCH_PAGE_EXHAUSTED",
          }),
          {
            status: 409,
            headers: { "content-type": "application/problem+json" },
          },
        ),
      ),
    );

    render(<SearchPagination searchId={testIds.search} />);
    await user.click(screen.getByRole("button", { name: "Next results" }));

    expect(
      await screen.findByText(
        "This search snapshot has no additional provider page.",
      ),
    ).toBeVisible();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("rejects a response that loops back to the current snapshot", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(searchResponseFixture()), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    render(<SearchPagination searchId={testIds.search} />);
    await user.click(screen.getByRole("button", { name: "Next results" }));

    expect(
      await screen.findByText(
        "The backend returned an unexpected search response.",
      ),
    ).toBeVisible();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("is shown only when the search response advertises a next cursor", () => {
    const search = searchResponseFixture();
    const { rerender } = render(<SearchResults search={search} />);

    expect(
      screen.getByRole("navigation", { name: "Search result pages" }),
    ).toBeVisible();

    rerender(<SearchResults search={{ ...search, nextCursor: null }} />);
    expect(
      screen.queryByRole("navigation", { name: "Search result pages" }),
    ).not.toBeInTheDocument();
  });
});
