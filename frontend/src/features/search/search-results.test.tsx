import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { SearchResults } from "@/features/search/search-results";
import { searchResponseFixture } from "@/test/fixtures";

afterEach(cleanup);

describe("SearchResults", () => {
  it("shows useful results without exposing cache, provider, or ranking diagnostics", () => {
    render(
      <SearchResults
        search={{
          ...searchResponseFixture(),
          cacheDisposition: "EXACT_HIT",
          nextCursor: null,
          warnings: ["OPENALEX_TIMEOUT"],
        }}
      />,
    );

    expect(
      screen.getByRole("heading", {
        name: "graph neural networks for drug discovery",
      }),
    ).toBeVisible();
    expect(
      screen.getByRole("link", {
        name: "Graph neural networks for molecular property prediction",
      }),
    ).toBeVisible();
    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "Some research sources could not be reached.",
    );
    expect(screen.queryByText("OPENALEX_TIMEOUT")).not.toBeInTheDocument();
    expect(screen.queryByText("Exact Hit")).not.toBeInTheDocument();
    expect(screen.queryByText("Provider coverage")).not.toBeInTheDocument();
    expect(screen.queryByText(/Score:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Fresh until/)).not.toBeInTheDocument();
  });
});
