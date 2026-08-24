import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SearchForm } from "@/features/search/search-form";
import { searchResponseFixture, testIds } from "@/test/fixtures";

const navigation = vi.hoisted(() => ({
  push: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

function jsonResponse(status: number, body: unknown): Response {
  return {
    json: vi.fn().mockResolvedValue(body),
    ok: status >= 200 && status < 300,
    status,
  } as unknown as Response;
}

afterEach(() => {
  cleanup();
  navigation.push.mockReset();
  vi.unstubAllGlobals();
});

describe("SearchForm", () => {
  it.each([200, 201])(
    "submits the bounded search request and navigates after a %i response",
    async (status) => {
      const user = userEvent.setup();
      const fetchMock = vi
        .fn()
        .mockResolvedValue(jsonResponse(status, searchResponseFixture()));
      vi.stubGlobal("fetch", fetchMock);

      render(
        <SearchForm initialQuery="  graph neural networks for drug discovery  " />,
      );

      await user.click(screen.getByText("Refine search"));
      await user.type(screen.getByLabelText("From year"), "2020");
      await user.type(screen.getByLabelText("To year"), "2026");
      await user.clear(screen.getByLabelText("Minimum citations"));
      await user.type(screen.getByLabelText("Minimum citations"), "12");
      await user.selectOptions(screen.getByLabelText("Language"), "en");
      await user.click(screen.getByRole("checkbox", { name: "Article" }));
      await user.click(screen.getByRole("checkbox", { name: "Thesis" }));
      await user.click(
        screen.getByRole("checkbox", {
          name: "Show papers marked as open access",
        }),
      );
      await user.click(screen.getByRole("button", { name: "Search papers" }));

      await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
      expect(fetchMock).toHaveBeenCalledWith("/api/searches", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          query: "graph neural networks for drug discovery",
          mode: "AUTO",
          filters: {
            yearFrom: 2020,
            yearTo: 2026,
            documentTypes: ["ARTICLE", "THESIS"],
            openAccessOnly: true,
            minimumCitations: 12,
            languages: ["en"],
          },
          pageSize: 20,
          forceRefresh: false,
        }),
      });
      await waitFor(() =>
        expect(navigation.push).toHaveBeenCalledWith(
          `/searches/${testIds.search}`,
        ),
      );
    },
  );

  it("offers a local search without exposing provider controls", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);

    render(<SearchForm initialQuery="protein structure prediction" />);
    await user.click(screen.getByRole("button", { name: "Search locally" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenCalledWith("/api/searches", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        query: "protein structure prediction",
        mode: "LOCAL",
        filters: {
          documentTypes: [],
          openAccessOnly: false,
          minimumCitations: 0,
          languages: [],
        },
        pageSize: 20,
        forceRefresh: false,
      }),
    });
    expect(
      screen.getByText("Searching papers already known to OpenScholar."),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Search papers" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Searching locally…" }),
    ).toBeDisabled();
    expect(screen.queryByText("Provider coverage")).not.toBeInTheDocument();
  });

  it("surfaces an RFC 9457 validation problem and its violations", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(400, {
        type: "urn:openscholar:problem:validation-failed",
        title: "Request validation failed",
        status: 400,
        detail: "One or more request fields are invalid.",
        instance: "/api/v1/searches",
        code: "VALIDATION_FAILED",
        violations: [
          {
            field: "filters.yearFrom",
            message: "must be greater than or equal to 1000",
          },
        ],
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    render(<SearchForm initialQuery="protein structure prediction" />);
    await user.click(screen.getByRole("button", { name: "Search papers" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("One or more request fields are invalid.");
    expect(alert).toHaveTextContent(
      "filters.yearFrom: must be greater than or equal to 1000",
    );
    expect(screen.getByLabelText("From year")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("rejects an inverted year range before making a request", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    render(<SearchForm initialQuery="retrieval augmented generation" />);
    await user.click(screen.getByText("Refine search"));
    await user.type(screen.getByLabelText("From year"), "2026");
    await user.type(screen.getByLabelText("To year"), "2020");
    await user.click(screen.getByRole("button", { name: "Search papers" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Review the highlighted search values.");
    expect(alert).toHaveTextContent("filters.yearTo:");
    expect(alert).toHaveTextContent("Start year must not be after end year.");
    expect(screen.getByLabelText("To year")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
