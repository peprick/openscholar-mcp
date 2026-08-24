import "server-only";

import type { ZodType } from "zod";

import {
  collectionDetailsResponseSchema,
  collectionListResponseSchema,
  collectionSummarySchema,
  savedLibraryResponseSchema,
  savedPaperSchema,
  type CollectionDetailsResponse,
  type CollectionListResponse,
  type CollectionSummary,
  type CreateCollectionRequest,
  type SavedLibraryQuery,
  type SavedLibraryResponse,
  type SavedPaper,
  type SavedPaperMutation,
  type UpdateCollectionRequest,
} from "@/shared/api/library-schemas";
import {
  apiProblemSchema,
  paperAccessResponseSchema,
  paperDetailsResponseSchema,
  paperIdentifierResolutionSchema,
  relatedPapersResponseSchema,
  searchResponseSchema,
  systemStatusResponseSchema,
  type ApiProblem,
  type CreateSearchRequest,
  type PaperAccessResponse,
  type PaperDetailsResponse,
  type PaperIdentifierResolution,
  type RelatedPapersResponse,
  type SearchResponse,
  type SystemStatusResponse,
} from "@/shared/api/schemas";
import type { DeletePersonalDataRequest } from "@/shared/api/privacy-schemas";
import { getBackendAccessToken } from "@/shared/auth/session";

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
    const headers = new Headers(init.headers);
    if (!headers.has("authorization")) {
      const accessToken = await getBackendAccessToken();
      if (accessToken !== null) {
        headers.set("authorization", `Bearer ${accessToken}`);
      }
    }
    return await fetch(backendUrl(path), {
      ...init,
      headers,
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

async function requestEmpty(path: string, init: RequestInit): Promise<void> {
  const response = await fetchBackend(path, init);
  if (!response.ok) {
    throw new BackendApiError(
      response.status,
      await problemFrom(response),
      response.headers.get("retry-after"),
    );
  }
}

async function requireExpectedSuccess(
  response: Response,
  expectedStatus: number,
  resource: string,
): Promise<void> {
  if (!response.ok) {
    throw new BackendApiError(
      response.status,
      await problemFrom(response),
      response.headers.get("retry-after"),
    );
  }
  if (response.status !== expectedStatus) {
    await response.body?.cancel().catch(() => undefined);
    throw new BackendContractError(resource);
  }
}

function hasJsonContentType(response: Response): boolean {
  const contentType = response.headers.get("content-type");
  return (
    contentType?.split(";", 1)[0]?.trim().toLowerCase() === "application/json"
  );
}

function paginationQuery(page: number, size: number): URLSearchParams {
  return new URLSearchParams({ page: String(page), size: String(size) });
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

export async function getNextSearchPage(
  searchId: string,
): Promise<{ data: SearchResponse; status: number; location: string | null }> {
  return requestJson(
    `/api/v1/searches/${encodeURIComponent(searchId)}/next`,
    searchResponseSchema,
    "next search page",
    { method: "POST" },
  );
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

export async function resolvePaperIdentifier(
  identifier: string,
): Promise<PaperIdentifierResolution> {
  const query = new URLSearchParams({ identifier });
  return (await requestJson(
    `/api/v1/papers/resolve?${query}`,
    paperIdentifierResolutionSchema,
    "paper identifier resolution",
  )).data;
}

export async function getRelatedPapers(
  paperId: string,
  limit = 10,
): Promise<RelatedPapersResponse> {
  const query = new URLSearchParams({ limit: String(limit) });
  const related = (await requestJson(
    `/api/v1/papers/${encodeURIComponent(paperId)}/related?${query}`,
    relatedPapersResponseSchema,
    "related papers",
  )).data;
  if (related.sourcePaperId.toLowerCase() !== paperId.toLowerCase()) {
    throw new BackendContractError("related papers");
  }
  return related;
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

export async function getCollections(
  page = 0,
  size = 20,
): Promise<CollectionListResponse> {
  const query = paginationQuery(page, size);
  return (await requestJson(
    `/api/v1/collections?${query}`,
    collectionListResponseSchema,
    "collection list",
  )).data;
}

export async function getAllCollectionOptions(): Promise<CollectionSummary[]> {
  const first = await getCollections(0, 100);
  const collections = new Map(
    first.items.map((collection) => [collection.collectionId, collection]),
  );
  let totalPages = first.totalPages;
  for (let page = 1; page < totalPages; page += 1) {
    const result = await getCollections(page, 100);
    for (const collection of result.items) {
      collections.set(collection.collectionId, collection);
    }
    totalPages = Math.max(totalPages, result.totalPages);
  }
  return [...collections.values()];
}

export async function createCollection(
  request: CreateCollectionRequest,
): Promise<{
  data: CollectionListResponse["items"][number];
  status: number;
  location: string | null;
}> {
  return requestJson(
    "/api/v1/collections",
    collectionSummarySchema,
    "collection",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(request),
    },
  );
}

export async function getCollection(
  collectionId: string,
  page = 0,
  size = 20,
): Promise<CollectionDetailsResponse> {
  const query = paginationQuery(page, size);
  return (await requestJson(
    `/api/v1/collections/${encodeURIComponent(collectionId)}?${query}`,
    collectionDetailsResponseSchema,
    "collection details",
  )).data;
}

export async function updateCollection(
  collectionId: string,
  request: UpdateCollectionRequest,
): Promise<CollectionListResponse["items"][number]> {
  return (await requestJson(
    `/api/v1/collections/${encodeURIComponent(collectionId)}`,
    collectionSummarySchema,
    "collection",
    {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(request),
    },
  )).data;
}

export async function deleteCollection(collectionId: string): Promise<void> {
  await requestEmpty(`/api/v1/collections/${encodeURIComponent(collectionId)}`, {
    method: "DELETE",
  });
}

export async function saveCollectionPaper(
  collectionId: string,
  paperId: string,
  request: SavedPaperMutation,
  method: "PUT" | "PATCH" = "PUT",
): Promise<SavedPaper> {
  return (await requestJson(
    `/api/v1/collections/${encodeURIComponent(collectionId)}/papers/${encodeURIComponent(paperId)}`,
    savedPaperSchema,
    "saved paper",
    {
      method,
      headers: { "content-type": "application/json" },
      body: JSON.stringify(request),
    },
  )).data;
}

export async function deleteCollectionPaper(
  collectionId: string,
  paperId: string,
): Promise<void> {
  await requestEmpty(
    `/api/v1/collections/${encodeURIComponent(collectionId)}/papers/${encodeURIComponent(paperId)}`,
    { method: "DELETE" },
  );
}

export async function searchSavedLibrary(
  request: SavedLibraryQuery,
): Promise<SavedLibraryResponse> {
  const query = paginationQuery(request.page, request.size);
  if (request.q !== undefined) query.set("q", request.q);
  if (request.collectionId !== undefined) {
    query.set("collectionId", request.collectionId);
  }
  if (request.readingStatus !== undefined) {
    query.set("readingStatus", request.readingStatus);
  }
  if (request.tag !== undefined) query.set("tag", request.tag);
  return (await requestJson(
    `/api/v1/library/papers?${query}`,
    savedLibraryResponseSchema,
    "saved library",
  )).data;
}

export async function exportPersonalData(): Promise<Response> {
  const response = await fetchBackend("/api/v1/privacy/export", {
    method: "GET",
    headers: { accept: "application/json" },
  });
  await requireExpectedSuccess(response, 200, "personal data export");
  if (!hasJsonContentType(response) || response.body === null) {
    await response.body?.cancel().catch(() => undefined);
    throw new BackendContractError("personal data export");
  }
  return response;
}

export async function deletePersonalData(
  request: DeletePersonalDataRequest,
): Promise<void> {
  const response = await fetchBackend("/api/v1/privacy/account", {
    method: "DELETE",
    headers: {
      accept: "application/json, application/problem+json",
      "content-type": "application/json",
    },
    body: JSON.stringify(request),
  });
  await requireExpectedSuccess(response, 204, "personal data deletion");
}
