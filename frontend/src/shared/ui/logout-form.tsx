"use client";

import { useRef, useState } from "react";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";

export function LogoutForm(): React.JSX.Element {
  const allowNativeSubmit = useRef(false);
  const [pending, setPending] = useState(false);

  async function prepareLogout(
    event: React.FormEvent<HTMLFormElement>,
  ): Promise<void> {
    if (allowNativeSubmit.current) return;
    event.preventDefault();
    if (pending) return;
    const form = event.currentTarget;
    setPending(true);

    try {
      const runtime = await loadOfflinePackRuntime();
      await runtime.beginDeletion();
    } catch {
      // Logout still proceeds. A successful same-origin response asks
      // supporting browsers to clear origin storage as defense in depth.
    }

    // The durable deletion fence is intentionally not completed here. A
    // successful logout removes it with Clear-Site-Data; an uncertain result
    // must remain fail-closed against a late save in another tab.
    allowNativeSubmit.current = true;
    form.requestSubmit();
  }

  return (
    <form
      action="/api/auth/logout"
      className="authNavForm"
      method="post"
      onSubmit={(event) => void prepareLogout(event)}
    >
      <button disabled={pending} type="submit">
        {pending ? "Signing out…" : "Sign out"}
      </button>
    </form>
  );
}
