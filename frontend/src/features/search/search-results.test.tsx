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

  it("explains an intentional local search in plain language", () => {
    render(
      <SearchResults
        search={{
          ...searchResponseFixture(),
          requestedMode: "LOCAL",
          executionSource: "LOCAL_CATALOG",
          cacheDisposition: "LOCAL_RESULT",
          nextCursor: null,
          providerCoverage: [],
          warnings: ["SHOWING_LOCAL_RESULTS"],
        }}
      />,
    );

    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "Local results.",
    );
    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "already saved or previously discovered",
    );
    expect(
      screen.getByText("1 previously discovered paper available"),
    ).toBeVisible();
    expect(screen.queryByText("SHOWING_LOCAL_RESULTS")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Some research sources could not be reached."),
    ).not.toBeInTheDocument();
  });

  it("distinguishes automatic local fallback from an intentional local search", () => {
    render(
      <SearchResults
        search={{
          ...searchResponseFixture(),
          requestedMode: "AUTO",
          executionSource: "LOCAL_CATALOG",
          cacheDisposition: "LOCAL_RESULT",
          nextCursor: null,
          providerCoverage: [],
        }}
      />,
    );

    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "Online research sources are unavailable.",
    );
    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "Showing previously discovered papers",
    );
    expect(screen.queryByText("Local results.")).not.toBeInTheDocument();
  });

  it("uses local guidance when the local catalog has no match", () => {
    render(
      <SearchResults
        search={{
          ...searchResponseFixture(),
          requestedMode: "LOCAL",
          executionSource: "LOCAL_CATALOG",
          cacheDisposition: "LOCAL_RESULT",
          nextCursor: null,
          providerCoverage: [],
          warnings: [],
          results: [],
        }}
      />,
    );

    expect(
      screen.getByRole("heading", {
        name: "No locally known papers matched this search.",
      }),
    ).toBeVisible();
    expect(
      screen.getByText(
        "Try a broader topic, or search online for newer research.",
      ),
    ).toBeVisible();
  });

  it("labels a stale cached response without showing cache diagnostics", () => {
    render(
      <SearchResults
        search={{
          ...searchResponseFixture(),
          executionSource: "STALE_CACHE",
          cacheDisposition: "STALE_FALLBACK",
          nextCursor: null,
          warnings: ["OPENALEX_TIMEOUT"],
        }}
      />,
    );

    expect(screen.getByLabelText("Search notice")).toHaveTextContent(
      "Showing earlier results.",
    );
    expect(screen.queryByText("Stale Fallback")).not.toBeInTheDocument();
    expect(screen.queryByText("OPENALEX_TIMEOUT")).not.toBeInTheDocument();
  });
});
