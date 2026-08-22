import type { Route } from "next";
import Link from "next/link";

import { getAuthConfig } from "@/shared/auth/config";
import {
  accessTokenIsCurrent,
  getRequestAuthSession,
} from "@/shared/auth/session";

export async function AuthNavigation(): Promise<React.JSX.Element | null> {
  let authenticated: boolean;
  try {
    const config = getAuthConfig();
    if (config.mode === "local") return null;
    const session = await getRequestAuthSession();
    authenticated = accessTokenIsCurrent(session);
  } catch {
    return null;
  }

  if (!authenticated) {
    return (
      <Link className="authNavLink" href={"/api/auth/login" as Route}>
        Sign in
      </Link>
    );
  }
  return (
    <form action="/api/auth/logout" className="authNavForm" method="post">
      <button type="submit">Sign out</button>
    </form>
  );
}
