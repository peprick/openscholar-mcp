import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { LibraryDashboard } from "@/features/library/library-dashboard";
import {
  collectionListFixture,
  collectionSummaryFixture,
  savedLibraryFixture,
  testIds,
} from "@/test/fixtures";

const navigation = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn() }));

vi.mock("next/navigation", () => ({ useRouter: () => navigation }));

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  navigation.push.mockReset();
  navigation.refresh.mockReset();
});

describe("LibraryDashboard", () => {
  it("exports a selected canonical paper as a bounded BibTeX request", async () => {
    const user = userEvent.setup();
    const response = new Response("@article{openscholar}", {
      status: 200,
      headers: {
        "content-type": "application/x-bibtex",
        "content-disposition": 'attachment; filename="research-set.bib"',
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(response);
    vi.stubGlobal("fetch", fetchMock);
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:library-export");
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => {});
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(function (this: HTMLAnchorElement) {
        expect(this.isConnected).toBe(true);
      });

    render(
      <LibraryDashboard
        collectionOptions={collectionListFixture().items}
        collections={collectionListFixture()}
        papers={savedLibraryFixture()}
        query={{ page: 0, size: 20 }}
      />,
    );

    await user.click(
      screen.getByRole("checkbox", {
        name: /Select Graph neural networks.+from Thesis foundations/,
      }),
    );
    const timeoutSpy = vi.spyOn(window, "setTimeout");
    fireEvent.click(screen.getByRole("button", { name: "Export BibTeX" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenCalledWith("/api/citations/export", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ paperIds: [testIds.paper], format: "bibtex" }),
    });
    expect(createObjectUrl).toHaveBeenCalled();
    expect(anchorClick).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    const cleanupCallIndex = timeoutSpy.mock.calls.findIndex(
      ([, delay]) => delay === 1_500,
    );
    expect(cleanupCallIndex).toBeGreaterThanOrEqual(0);
    const cleanupHandler = timeoutSpy.mock.calls[cleanupCallIndex]?.[0];
    const cleanupTimer = timeoutSpy.mock.results[cleanupCallIndex]?.value;
    window.clearTimeout(cleanupTimer);
    if (typeof cleanupHandler === "function") cleanupHandler();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:library-export");
    expect(
      await screen.findByText("Exported 1 selected papers."),
    ).toBeInTheDocument();
  });

  it("paginates collections independently while preserving library filters", () => {
    const collectionOptions = [
      collectionSummaryFixture(),
      ...Array.from({ length: 100 }, (_, index) =>
        collectionSummaryFixture({
          collectionId: `00000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
          name: `Collection option ${index + 1}`,
        }),
      ),
    ];
    const activeCollection = collectionOptions[100];
    if (activeCollection === undefined) throw new Error("Missing collection fixture");
    render(
      <LibraryDashboard
        collectionOptions={collectionOptions}
        collections={
          collectionListFixture(undefined, {
            page: 0,
            size: 12,
            totalElements: 13,
            totalPages: 2,
          })
        }
        papers={savedLibraryFixture()}
        query={{
          q: "graph",
          collectionId: activeCollection.collectionId,
          readingStatus: "READING",
          page: 2,
          size: 20,
        }}
      />,
    );

    const collectionFilter = screen.getByLabelText("Collection");
    expect(collectionFilter).toHaveValue(activeCollection.collectionId);
    expect((collectionFilter as HTMLSelectElement).options).toHaveLength(102);
    expect(screen.getByRole("link", { name: "Next" })).toHaveAttribute(
      "href",
      `/library?q=graph&collectionId=${activeCollection.collectionId}&readingStatus=READING&page=2&collectionsPage=1`,
    );
  });

  it("updates page-zero collection metadata immediately after creation", async () => {
    const user = userEvent.setup();
    const created = collectionSummaryFixture({
      collectionId: "a704dc47-bd36-4bf6-a008-f4bc354dccb1",
      name: "New review queue",
      paperCount: 0,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(created), {
          status: 201,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    const initialCollections = collectionListFixture();
    render(
      <LibraryDashboard
        collectionOptions={initialCollections.items}
        collections={initialCollections}
        papers={savedLibraryFixture()}
        query={{ page: 0, size: 20 }}
      />,
    );

    await user.click(
      screen.getByRole("checkbox", {
        name: /Select Graph neural networks.+from Thesis foundations/,
      }),
    );
    await user.type(screen.getByLabelText("Collection name"), "New review queue");
    await user.click(screen.getByRole("button", { name: "Create collection" }));

    expect(await screen.findByText("Created “New review queue”.")).toBeInTheDocument();
    expect(screen.getByText("2 collections")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New review queue" })).toBeInTheDocument();
    expect(screen.getByText("1 saved item")).toBeInTheDocument();
    expect(screen.queryByText(/memberships/i)).not.toBeInTheDocument();
    expect(screen.getByText("1", { selector: ".batchExportBar strong" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export BibTeX" })).toBeEnabled();
    expect(navigation.refresh).not.toHaveBeenCalled();
  });
});
