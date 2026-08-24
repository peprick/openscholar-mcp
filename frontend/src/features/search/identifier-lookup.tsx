"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import {
  paperIdentifierLookupRequestSchema,
  paperIdentifierResolutionSchema,
} from "@/shared/api/schemas";
import { useConnectivity } from "@/shared/connectivity/connectivity-context";

const LOOKUP_HINT_ID = "paper-identifier-hint";
const LOOKUP_ERROR_ID = "paper-identifier-error";

type LookupError = {
  identifierInvalid: boolean;
  message: string;
};

function failure(status: number): LookupError {
  if (status === 400) {
    return {
      identifierInvalid: true,
      message:
        "That doesn’t look like a supported paper identifier. Try a DOI, arXiv ID, or OpenAlex work ID.",
    };
  }
  if (status === 404) {
    return {
      identifierInvalid: false,
      message:
        "This paper isn’t in your OpenScholar workspace yet. Search for it by topic first.",
    };
  }
  if (status === 401 || status === 403) {
    return {
      identifierInvalid: false,
      message:
        "Your session can’t open this paper right now. Sign in again and retry.",
    };
  }
  if (status >= 500) {
    return {
      identifierInvalid: false,
      message: "OpenScholar is temporarily unavailable. Please try again.",
    };
  }
  return {
    identifierInvalid: false,
    message: "This paper could not be opened. Please try again.",
  };
}

export function IdentifierLookup(): React.JSX.Element {
  const router = useRouter();
  const { canReachApplication } = useConnectivity();
  const errorRef = useRef<HTMLParagraphElement>(null);
  const [pending, setPending] = useState(false);
  const [lookupError, setLookupError] = useState<LookupError | null>(null);

  useEffect(() => {
    if (lookupError !== null) {
      errorRef.current?.focus();
    }
  }, [lookupError]);

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (pending) return;

    setLookupError(null);
    const formData = new FormData(event.currentTarget);
    const parsedRequest = paperIdentifierLookupRequestSchema.safeParse({
      identifier: String(formData.get("identifier") ?? ""),
    });
    if (!parsedRequest.success) {
      setLookupError({
        identifierInvalid: true,
        message:
          parsedRequest.error.issues[0]?.message ?? "Enter a paper identifier.",
      });
      return;
    }

    setPending(true);
    try {
      const query = new URLSearchParams({
        identifier: parsedRequest.data.identifier,
      });
      const response = await fetch(`/api/papers/resolve?${query}`, {
        cache: "no-store",
      });
      const body: unknown = await response.json().catch(() => null);
      if (!response.ok) {
        setLookupError(failure(response.status));
        setPending(false);
        return;
      }

      const resolution = paperIdentifierResolutionSchema.safeParse(body);
      if (!resolution.success) {
        setLookupError({
          identifierInvalid: false,
          message: "OpenScholar received an unexpected response. Please try again.",
        });
        setPending(false);
        return;
      }

      router.push(`/papers/${resolution.data.paperId}` as Route);
    } catch {
      setLookupError({
        identifierInvalid: false,
        message: "OpenScholar is temporarily unavailable. Please try again.",
      });
      setPending(false);
    }
  }

  const describedBy =
    lookupError === null
      ? LOOKUP_HINT_ID
      : `${LOOKUP_HINT_ID} ${LOOKUP_ERROR_ID}`;

  return (
    <form
      aria-labelledby="paper-identifier-title"
      className="identifierLookup"
      noValidate
      onSubmit={submit}
    >
      <div className="identifierLookupIntro">
        <span>Already have a reference?</span>
        <strong id="paper-identifier-title">Open by identifier</strong>
      </div>
      <div className="identifierLookupControl">
        <label className="srOnly" htmlFor="paper-identifier">
          DOI, arXiv ID, or OpenAlex work ID
        </label>
        <input
          aria-describedby={describedBy}
          aria-invalid={lookupError?.identifierInvalid ?? false}
          autoCapitalize="off"
          autoComplete="off"
          id="paper-identifier"
          maxLength={512}
          name="identifier"
          placeholder="10.1038/… · arXiv:2401.12345 · W2741809807"
          spellCheck={false}
          type="text"
        />
        <button
          aria-describedby={
            !canReachApplication ? "app-connectivity-status" : undefined
          }
          className="button button--secondary"
          data-offline={!canReachApplication ? "true" : undefined}
          disabled={pending || !canReachApplication}
          type="submit"
        >
          {pending ? "Opening…" : "Open paper"}
        </button>
      </div>
      <p className="identifierLookupHint" id={LOOKUP_HINT_ID}>
        Opens a paper that is already in your OpenScholar workspace.
      </p>
      {lookupError !== null ? (
        <p
          className="identifierLookupError"
          id={LOOKUP_ERROR_ID}
          ref={errorRef}
          role="alert"
          tabIndex={-1}
        >
          {lookupError.message}
        </p>
      ) : null}
    </form>
  );
}
