import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CollectionManager } from "@/features/library/collection-manager";
import {
  collectionDetailsFixture,
  collectionSummaryFixture,
  savedPaperFixture,
  testIds,
} from "@/test/fixtures";

const navigation = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn() }));
const loadRuntime = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({ useRouter: () => navigation }));
vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: loadRuntime,
}));

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

beforeEach(() => {
  loadRuntime.mockReset();
  vi.stubGlobal("indexedDB", undefined);
});

afterEach(() => {
  cleanup();
  navigation.push.mockReset();
  navigation.refresh.mockReset();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("CollectionManager", () => {
  it("updates status and normalized tags, then removes the membership", async () => {
    const user = userEvent.setup();
    const updated = savedPaperFixture({
      readingStatus: "COMPLETED",
      tags: ["core method"],
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(updated))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.selectOptions(
      screen.getByLabelText(/Reading status for Graph neural networks/),
      "COMPLETED",
    );
    await user.clear(screen.getByLabelText(/Tags for Graph neural networks/));
    await user.type(
      screen.getByLabelText(/Tags for Graph neural networks/),
      " Core   Method ",
    );
    await user.click(
      screen.getByRole("button", { name: /Save changes for Graph neural networks/ }),
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/collections/${testIds.collection}/papers/${testIds.paper}`,
      {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          readingStatus: "COMPLETED",
          tags: ["core method"],
        }),
      },
    );

    await user.click(
      screen.getByRole("button", {
        name: /Remove Graph neural networks.+from Thesis foundations/,
      }),
    );
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/collections/${testIds.collection}/papers/${testIds.paper}`,
      { method: "DELETE" },
    );
    expect(screen.getByText("This collection is empty")).toBeInTheDocument();
  });

  it("renames and deletes a collection after explicit confirmation", async () => {
    const user = userEvent.setup();
    const summary = collectionSummaryFixture({
      name: "Renamed reading list",
      description: null,
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(summary))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.clear(screen.getByLabelText("Name"));
    await user.type(screen.getByLabelText("Name"), "Renamed reading list");
    await user.clear(screen.getByLabelText("Description"));
    await user.click(screen.getByRole("button", { name: "Save details" }));

    expect(await screen.findByText("Collection details saved.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Delete collection" }));
    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/library"));
    expect(navigation.refresh).toHaveBeenCalledOnce();
  });

  it("opens a durable collection-deletion fence and completes it after confirmed deletion", async () => {
    const user = userEvent.setup();
    const fence = {
      collectionDigest: "opaque-collection-digest",
      deletionId: "opaque-deletion-id",
    };
    const beginDeletion = vi.fn().mockResolvedValue(fence);
    const completeDeletion = vi.fn().mockResolvedValue(true);
    loadRuntime.mockResolvedValue({ beginDeletion, completeDeletion });
    vi.stubGlobal("indexedDB", {});
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    await waitFor(() => expect(completeDeletion).toHaveBeenCalledWith(fence));
    expect(beginDeletion).toHaveBeenCalledWith(testIds.collection);
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/collections/${testIds.collection}`,
      { method: "DELETE" },
    );
    expect(beginDeletion.mock.invocationCallOrder[0]).toBeLessThan(
      fetchMock.mock.invocationCallOrder[0]!,
    );
    expect(fetchMock.mock.invocationCallOrder[0]).toBeLessThan(
      completeDeletion.mock.invocationCallOrder[0]!,
    );
  });

  it("does not delete the server collection when local cleanup fails", async () => {
    const user = userEvent.setup();
    loadRuntime.mockResolvedValue({
      beginDeletion: vi.fn().mockRejectedValue(new Error("storage blocked")),
      completeDeletion: vi.fn(),
    });
    vi.stubGlobal("indexedDB", {});
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    expect(
      await screen.findByText(
        "The encrypted offline copy could not be cleared, so the collection was not deleted.",
      ),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("leaves the deletion fence in place after a non-success server response", async () => {
    const user = userEvent.setup();
    const fence = {
      collectionDigest: "opaque-collection-digest",
      deletionId: "opaque-deletion-id",
    };
    const completeDeletion = vi.fn();
    loadRuntime.mockResolvedValue({
      beginDeletion: vi.fn().mockResolvedValue(fence),
      completeDeletion,
    });
    vi.stubGlobal("indexedDB", {});
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            title: "Request failed",
            status: 503,
            detail: "Deletion could not be confirmed.",
            code: "BACKEND_UNREACHABLE",
          },
          503,
        ),
      ),
    );
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    expect(
      await screen.findByText("Deletion could not be confirmed."),
    ).toBeInTheDocument();
    expect(completeDeletion).not.toHaveBeenCalled();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("leaves the deletion fence in place when the server result is unknown", async () => {
    const user = userEvent.setup();
    const completeDeletion = vi.fn();
    loadRuntime.mockResolvedValue({
      beginDeletion: vi.fn().mockResolvedValue({
        collectionDigest: "opaque-collection-digest",
        deletionId: "opaque-deletion-id",
      }),
      completeDeletion,
    });
    vi.stubGlobal("indexedDB", {});
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("response lost after send")),
    );
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    expect(
      await screen.findByText("OpenScholar could not reach the library service."),
    ).toBeInTheDocument();
    expect(completeDeletion).not.toHaveBeenCalled();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("continues after confirmed deletion when the browser fence was already cleared", async () => {
    const user = userEvent.setup();
    const completeDeletion = vi.fn().mockResolvedValue(false);
    loadRuntime.mockResolvedValue({
      beginDeletion: vi.fn().mockResolvedValue({
        collectionDigest: "opaque-collection-digest",
        deletionId: "opaque-deletion-id",
      }),
      completeDeletion,
    });
    vi.stubGlobal("indexedDB", {});
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    await waitFor(() => expect(navigation.push).toHaveBeenCalledWith("/library"));
    expect(completeDeletion).toHaveBeenCalledOnce();
    expect(navigation.refresh).toHaveBeenCalledOnce();
    expect(
      screen.queryByText(
        "The collection was deleted, but browser cleanup could not be completed. Refresh before saving another offline copy.",
      ),
    ).not.toBeInTheDocument();
  });

  it("reports local completion errors after confirmed server deletion", async () => {
    const user = userEvent.setup();
    const completeDeletion = vi
      .fn()
      .mockRejectedValue(new Error("browser storage unavailable"));
    loadRuntime.mockResolvedValue({
      beginDeletion: vi.fn().mockResolvedValue({
        collectionDigest: "opaque-collection-digest",
        deletionId: "opaque-deletion-id",
      }),
      completeDeletion,
    });
    vi.stubGlobal("indexedDB", {});
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<CollectionManager collection={collectionDetailsFixture()} />);

    await user.click(screen.getByRole("button", { name: "Delete collection" }));

    expect(
      await screen.findByText(
        "The collection was deleted, but browser cleanup could not be completed. Refresh before saving another offline copy.",
      ),
    ).toBeInTheDocument();
    expect(completeDeletion).toHaveBeenCalledOnce();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("returns to the preceding page after removing its final membership", async () => {
    const user = userEvent.setup();
    const collection = collectionDetailsFixture();
    collection.paperCount = 21;
    collection.papers = {
      ...collection.papers,
      page: 1,
      totalElements: 21,
      totalPages: 2,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    render(<CollectionManager collection={collection} />);

    await user.click(
      screen.getByRole("button", {
        name: /Remove Graph neural networks.+from Thesis foundations/,
      }),
    );

    await waitFor(() =>
      expect(navigation.push).toHaveBeenCalledWith(
        `/library/collections/${testIds.collection}?page=0`,
      ),
    );
    expect(navigation.refresh).not.toHaveBeenCalled();
  });
});
