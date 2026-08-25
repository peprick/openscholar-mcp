import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import { LogoutForm } from "@/shared/ui/logout-form";

vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: vi.fn(),
}));

const runtime = {
  beginDeletion: vi.fn(),
  completeDeletion: vi.fn(),
};
const deletionFence = {
  collectionDigest: null,
  deletionId: "opaque-logout-deletion-id",
};
let requestSubmit: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  runtime.beginDeletion.mockReset().mockResolvedValue(deletionFence);
  runtime.completeDeletion.mockReset();
  requestSubmit = vi
    .spyOn(HTMLFormElement.prototype, "requestSubmit")
    .mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  requestSubmit.mockRestore();
  vi.mocked(loadOfflinePackRuntime).mockReset();
  runtime.beginDeletion.mockReset();
  runtime.completeDeletion.mockReset();
});

describe("logout form", () => {
  it("opens a durable deletion fence before native logout and leaves it in place", async () => {
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);
    render(<LogoutForm />);

    fireEvent.submit(screen.getByRole("button", { name: "Sign out" }).closest("form")!);

    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
    await waitFor(() => expect(runtime.beginDeletion).toHaveBeenCalledWith());
    expect(requestSubmit).toHaveBeenCalledOnce();
    expect(runtime.beginDeletion.mock.invocationCallOrder[0]).toBeLessThan(
      requestSubmit.mock.invocationCallOrder[0]!,
    );
    expect(runtime.completeDeletion).not.toHaveBeenCalled();
  });

  it("still signs out when targeted browser cleanup is unavailable", async () => {
    vi.mocked(loadOfflinePackRuntime).mockRejectedValue(
      new Error("unsupported browser"),
    );
    render(<LogoutForm />);

    fireEvent.submit(screen.getByRole("button", { name: "Sign out" }).closest("form")!);

    await waitFor(() => expect(requestSubmit).toHaveBeenCalledOnce());
    expect(runtime.beginDeletion).not.toHaveBeenCalled();
    expect(runtime.completeDeletion).not.toHaveBeenCalled();
  });
});
