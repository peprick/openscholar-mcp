import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import { LogoutForm } from "@/shared/ui/logout-form";

vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: vi.fn(),
}));

const runtime = {
  lock: vi.fn(),
  purge: vi.fn().mockResolvedValue(true),
};
let requestSubmit: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  requestSubmit = vi
    .spyOn(HTMLFormElement.prototype, "requestSubmit")
    .mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  requestSubmit.mockRestore();
  vi.mocked(loadOfflinePackRuntime).mockReset();
  runtime.lock.mockReset();
  runtime.purge.mockReset().mockResolvedValue(true);
});

describe("logout form", () => {
  it("locks and purges browser metadata before native logout", async () => {
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);
    render(<LogoutForm />);

    fireEvent.submit(screen.getByRole("button", { name: "Sign out" }).closest("form")!);

    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
    await waitFor(() => expect(runtime.purge).toHaveBeenCalledOnce());
    expect(runtime.lock).toHaveBeenCalledOnce();
    expect(requestSubmit).toHaveBeenCalledOnce();
  });

  it("still signs out when targeted browser cleanup is unavailable", async () => {
    vi.mocked(loadOfflinePackRuntime).mockRejectedValue(
      new Error("unsupported browser"),
    );
    render(<LogoutForm />);

    fireEvent.submit(screen.getByRole("button", { name: "Sign out" }).closest("form")!);

    await waitFor(() => expect(requestSubmit).toHaveBeenCalledOnce());
    expect(runtime.purge).not.toHaveBeenCalled();
  });
});
