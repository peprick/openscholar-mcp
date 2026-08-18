import "server-only";

import type { ZodType } from "zod";

import {
  apiProblemSchema,
  paperAccessResponseSchema,
  paperDetailsResponseSchema,
  searchResponseSchema,
  systemStatusResponseSchema,
  type ApiProblem,
  type CreateSearchRequest,
  type PaperAccessResponse,
  type PaperDetailsResponse,
  type SearchResponse,
  type SystemStatusResponse,
} from "@/shared/api/schemas";

const DEFAULT_BACKEND_ORIGIN = "http://localhost:8080";
const REQUEST_TIMEOUT_MS = 30_000;

export class BackendApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ApiProblem,
    readonly retryAfter: string | null = null,
  ) {
    super(problem.detail);
    this.name = "BackendApiError";
  }
}

export class BackendContractError extends Error {
  constructor(resource: string) {
    super(`The backend returned an unexpected ${resource} response.`);
    this.name = "BackendContractError";
  }
}

function backendOrigin(): URL {
  const configured = process.env.OPENSCHOLAR_API_BASE_URL ?? DEFAULT_BACKEND_ORIGIN;
  const url = new URL(configured);
  if (
    (url.protocol !== "http:" && url.protocol !== "https:") ||
    url.username !== "" ||
    url.password !== ""
  ) {
    throw new Error("OPENSCHOLAR_API_BASE_URL must be a credential-free HTTP(S) URL.");
  }
  return url;
}

function backendUrl(path: string): URL {
  return new URL(path, backendOrigin());
}

function transportProblem(): ApiProblem {
  return {
    type: "urn:openscholar:problem:backend-unreachable",
    title: "Backend unavailable",
    status: 503,
    detail: "OpenScholar could not reach the research backend.",
    code: "BACKEND_UNREACHABLE",
    retryable: true,
  };
}

export async function fetchBackend(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  try {
    return await fetch(backendUrl(path), {
      ...init,
      cache: "no-store",
      signal: init.signal ?? AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    if (error instanceof BackendApiError) {
      throw error;
    }
    throw new BackendApiError(503, transportProblem(), null);
  }
}

async function problemFrom(response: Response): Promise<ApiProblem> {
  const fallback: ApiProblem = {
    type: "urn:openscholar:problem:unexpected-backend-response",
    title: "Request failed",
    status: response.status,
    detail: "The research backend could not complete this request.",
    code: "BACKEND_REQUEST_FAILED",
  };
  try {
    const parsed = apiProblemSchema.safeParse(await response.json());
    return parsed.success ? parsed.data : fallback;
  } catch {
    return fallback;
  }
}

async function requestJson<T>(
  path: string,
  schema: ZodType<T>,
  resource: string,
  init: RequestInit = {},
): Promise<{ data: T; status: number; location: string | null }> {
  const response = await fetchBackend(path, init);
  if (!response.ok) {
    throw new BackendApiError(
      response.status,
      await problemFrom(response),
      response.headers.get("retry-after"),
    );
  }

  const parsed = schema.safeParse(await response.json());
  if (!parsed.success) {
    throw new BackendContractError(resource);
  }
  return {
    data: parsed.data,
    status: response.status,
    location: response.headers.get("location"),
  };
}

export async function getSystemStatus(): Promise<SystemStatusResponse> {
  return (await requestJson(
    "/api/v1/system/status",
    systemStatusResponseSchema,
    "system status",
    { signal: AbortSignal.timeout(2_500) },
  )).data;
}

export async function createSearch(
  request: CreateSearchRequest,
): Promise<{ data: SearchResponse; status: number; location: string | null }> {
  return requestJson("/api/v1/searches", searchResponseSchema, "search", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
}

export async function getSearch(searchId: string): Promise<SearchResponse> {
  return (await requestJson(
    `/api/v1/searches/${encodeURIComponent(searchId)}`,
    searchResponseSchema,
    "saved search",
  )).data;
}

export async function getPaperDetails(
  paperId: string,
): Promise<PaperDetailsResponse> {
  return (await requestJson(
    `/api/v1/papers/${encodeURIComponent(paperId)}`,
    paperDetailsResponseSchema,
    "paper details",
  )).data;
}

export async function getPaperAccess(
  paperId: string,
): Promise<PaperAccessResponse> {
  return (await requestJson(
    `/api/v1/papers/${encodeURIComponent(paperId)}/versions`,
    paperAccessResponseSchema,
    "paper access",
  )).data;
}

export async function verifyPaperAccess(
  paperId: string,
  forceRefresh: boolean,
): Promise<PaperAccessResponse> {
  const query = new URLSearchParams({ forceRefresh: String(forceRefresh) });
  return (await requestJson(
    `/api/v1/papers/${encodeURIComponent(paperId)}/access/verify?${query}`,
    paperAccessResponseSchema,
    "paper access verification",
    { method: "POST" },
  )).data;
}
