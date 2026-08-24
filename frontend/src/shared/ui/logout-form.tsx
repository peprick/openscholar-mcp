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
      runtime.lock();
      await runtime.purge();
    } catch {
      // Logout still proceeds. The successful same-origin response also asks
      // supporting browsers to clear origin storage as defense in depth.
    }

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
