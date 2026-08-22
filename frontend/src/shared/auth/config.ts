import "server-only";

const DEFAULT_SESSION_MAX_AGE_SECONDS = 8 * 60 * 60;
const MIN_SESSION_MAX_AGE_SECONDS = 5 * 60;
const MAX_SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

export type LocalAuthConfig = {
  mode: "local";
};

export type OidcClientAuthMethod =
  | "none"
  | "client_secret_basic"
  | "client_secret_post";

export type OidcAuthConfig = {
  mode: "oidc";
  issuer: string;
  authorizationEndpoint: URL;
  tokenEndpoint: URL;
  jwksUri: URL;
  endSessionEndpoint: URL | null;
  clientId: string;
  clientSecret: string | null;
  clientAuthMethod: OidcClientAuthMethod;
  redirectUri: URL;
  postLogoutRedirectUri: URL;
  scopes: readonly string[];
  idTokenAlgorithms: readonly ("RS256" | "PS256" | "ES256")[];
  sessionKey: Buffer;
  sessionMaxAgeSeconds: number;
};

export type AuthConfig = LocalAuthConfig | OidcAuthConfig;

export class AuthConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthConfigurationError";
  }
}

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new AuthConfigurationError(`${name} is required in OIDC mode.`);
  }
  return value;
}

function optional(name: string): string | null {
  const value = process.env[name]?.trim();
  return value ? value : null;
}

function secureUrl(name: string, value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new AuthConfigurationError(`${name} must be an absolute URL.`);
  }
  if (
    url.protocol !== "https:" ||
    url.username !== "" ||
    url.password !== "" ||
    url.hash !== ""
  ) {
    throw new AuthConfigurationError(
      `${name} must be a credential-free HTTPS URL without a fragment.`,
    );
  }
  return url;
}

function parseClientAuthMethod(): OidcClientAuthMethod {
  const value = optional("OPENSCHOLAR_OIDC_CLIENT_AUTH_METHOD") ?? "none";
  if (
    value !== "none" &&
    value !== "client_secret_basic" &&
    value !== "client_secret_post"
  ) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_CLIENT_AUTH_METHOD must be none, client_secret_basic, or client_secret_post.",
    );
  }
  return value;
}

function parseScopes(): readonly string[] {
  const configured =
    optional("OPENSCHOLAR_OIDC_SCOPES") ??
    "openid profile openscholar.search openscholar.library openscholar.jobs openscholar.privacy";
  const scopes = [...new Set(configured.split(/\s+/u).filter(Boolean))];
  if (!scopes.includes("openid")) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_SCOPES must include openid.",
    );
  }
  if (scopes.some((scope) => !/^[\x21\x23-\x5B\x5D-\x7E]+$/u.test(scope))) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_SCOPES contains an invalid scope value.",
    );
  }
  return scopes;
}

function parseAlgorithms(): readonly ("RS256" | "PS256" | "ES256")[] {
  const configured = optional("OPENSCHOLAR_OIDC_ID_TOKEN_ALGS") ?? "RS256";
  const algorithms = [...new Set(configured.split(/[,\s]+/u).filter(Boolean))];
  if (
    algorithms.length === 0 ||
    algorithms.some(
      (algorithm) =>
        algorithm !== "RS256" &&
        algorithm !== "PS256" &&
        algorithm !== "ES256",
    )
  ) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_ID_TOKEN_ALGS may contain only RS256, PS256, and ES256.",
    );
  }
  return algorithms as ("RS256" | "PS256" | "ES256")[];
}

function parseSessionKey(): Buffer {
  const encoded = required("OPENSCHOLAR_AUTH_SESSION_SECRET");
  if (!/^[A-Za-z0-9+/_-]+={0,2}$/u.test(encoded)) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_AUTH_SESSION_SECRET must be base64-encoded.",
    );
  }
  const key = Buffer.from(encoded.replace(/-/gu, "+").replace(/_/gu, "/"), "base64");
  if (key.length !== 32) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_AUTH_SESSION_SECRET must decode to exactly 32 bytes.",
    );
  }
  return key;
}

function parseSessionMaxAge(): number {
  const raw = optional("OPENSCHOLAR_AUTH_SESSION_MAX_AGE_SECONDS");
  if (raw === null) return DEFAULT_SESSION_MAX_AGE_SECONDS;
  const value = Number(raw);
  if (
    !Number.isSafeInteger(value) ||
    value < MIN_SESSION_MAX_AGE_SECONDS ||
    value > MAX_SESSION_MAX_AGE_SECONDS
  ) {
    throw new AuthConfigurationError(
      `OPENSCHOLAR_AUTH_SESSION_MAX_AGE_SECONDS must be an integer from ${MIN_SESSION_MAX_AGE_SECONDS} to ${MAX_SESSION_MAX_AGE_SECONDS}.`,
    );
  }
  return value;
}

export function getAuthConfig(): AuthConfig {
  const mode = optional("OPENSCHOLAR_AUTH_MODE") ?? "local";
  if (mode === "local") return { mode: "local" };
  if (mode !== "oidc") {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_AUTH_MODE must be local or oidc.",
    );
  }

  const clientAuthMethod = parseClientAuthMethod();
  const clientSecret = optional("OPENSCHOLAR_OIDC_CLIENT_SECRET");
  if (clientAuthMethod !== "none" && clientSecret === null) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_CLIENT_SECRET is required for the configured client authentication method.",
    );
  }

  const issuer = required("OPENSCHOLAR_OIDC_ISSUER");
  const issuerUrl = secureUrl("OPENSCHOLAR_OIDC_ISSUER", issuer);
  if (issuerUrl.search !== "") {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_ISSUER must not contain a query string.",
    );
  }
  const redirectUri = secureUrl(
    "OPENSCHOLAR_OIDC_REDIRECT_URI",
    required("OPENSCHOLAR_OIDC_REDIRECT_URI"),
  );
  const postLogoutRedirectUri = secureUrl(
    "OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI",
    required("OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI"),
  );
  if (
    redirectUri.pathname !== "/api/auth/callback" ||
    redirectUri.search !== ""
  ) {
    throw new AuthConfigurationError(
      "OPENSCHOLAR_OIDC_REDIRECT_URI must use the exact /api/auth/callback path without a query string.",
    );
  }
  if (redirectUri.origin !== postLogoutRedirectUri.origin) {
    throw new AuthConfigurationError(
      "OIDC redirect and post-logout redirect URIs must have the same origin.",
    );
  }

  const endSessionEndpoint = optional("OPENSCHOLAR_OIDC_END_SESSION_ENDPOINT");
  return {
    mode: "oidc",
    issuer,
    authorizationEndpoint: secureUrl(
      "OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT",
      required("OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT"),
    ),
    tokenEndpoint: secureUrl(
      "OPENSCHOLAR_OIDC_TOKEN_ENDPOINT",
      required("OPENSCHOLAR_OIDC_TOKEN_ENDPOINT"),
    ),
    jwksUri: secureUrl(
      "OPENSCHOLAR_OIDC_JWKS_URI",
      required("OPENSCHOLAR_OIDC_JWKS_URI"),
    ),
    endSessionEndpoint:
      endSessionEndpoint === null
        ? null
        : secureUrl("OPENSCHOLAR_OIDC_END_SESSION_ENDPOINT", endSessionEndpoint),
    clientId: required("OPENSCHOLAR_OIDC_CLIENT_ID"),
    clientSecret,
    clientAuthMethod,
    redirectUri,
    postLogoutRedirectUri,
    scopes: parseScopes(),
    idTokenAlgorithms: parseAlgorithms(),
    sessionKey: parseSessionKey(),
    sessionMaxAgeSeconds: parseSessionMaxAge(),
  };
}
