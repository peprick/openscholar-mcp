"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import {
  apiProblemSchema,
  createSearchRequestSchema,
  documentTypes,
  searchResponseSchema,
} from "@/shared/api/schemas";
import { humanizeEnum } from "@/shared/formatting/display";

type SearchFormProps = {
  initialQuery?: string;
};

function optionalInteger(formData: FormData, name: string): number | undefined {
  const value = String(formData.get(name) ?? "").trim();
  return value === "" ? undefined : Number(value);
}

function requestFrom(form: HTMLFormElement): unknown {
  const formData = new FormData(form);
  const language = String(formData.get("language") ?? "").trim();
  return {
    query: String(formData.get("query") ?? ""),
    filters: {
      yearFrom: optionalInteger(formData, "yearFrom"),
      yearTo: optionalInteger(formData, "yearTo"),
      documentTypes: formData
        .getAll("documentTypes")
        .map((value) => String(value)),
      openAccessOnly: formData.get("openAccessOnly") === "on",
      minimumCitations: optionalInteger(formData, "minimumCitations") ?? 0,
      languages: language === "" ? [] : [language],
    },
    pageSize: 20,
    forceRefresh: false,
  };
}

function formFieldFromPath(path: PropertyKey[]): string | null {
  const field = path.at(-1);
  return typeof field === "string" ? field : null;
}

function formFieldFromViolation(field: string): string {
  return field.split(".").at(-1) ?? field;
}

export function SearchForm({
  initialQuery = "",
}: SearchFormProps): React.JSX.Element {
  const router = useRouter();
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const [pending, setPending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [violations, setViolations] = useState<string[]>([]);
  const [invalidFields, setInvalidFields] = useState<string[]>([]);

  useEffect(() => {
    if (errorMessage !== null) {
      errorSummaryRef.current?.focus();
    }
  }, [errorMessage]);

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setErrorMessage(null);
    setViolations([]);
    setInvalidFields([]);

    const parsedRequest = createSearchRequestSchema.safeParse(
      requestFrom(event.currentTarget),
    );
    if (!parsedRequest.success) {
      setErrorMessage("Review the highlighted search values.");
      setViolations(
        parsedRequest.error.issues.map((issue) => {
          const path = issue.path.join(".");
          return path === "" ? issue.message : `${path}: ${issue.message}`;
        }),
      );
      setInvalidFields(
        parsedRequest.error.issues
          .map((issue) => formFieldFromPath(issue.path))
          .filter((field): field is string => field !== null),
      );
      return;
    }

    setPending(true);
    try {
      const response = await fetch("/api/searches", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(parsedRequest.data),
      });
      const body: unknown = await response.json();

      if (!response.ok) {
        const problem = apiProblemSchema.safeParse(body);
        setErrorMessage(
          problem.success
            ? problem.data.detail
            : "The research search could not be completed.",
        );
        setViolations(
          problem.success
            ? (problem.data.violations?.map(
                (violation) => `${violation.field}: ${violation.message}`,
              ) ?? [])
            : [],
        );
        setInvalidFields(
          problem.success
            ? (problem.data.violations?.map((violation) =>
                formFieldFromViolation(violation.field),
              ) ?? [])
            : [],
        );
        return;
      }

      const search = searchResponseSchema.safeParse(body);
      if (!search.success) {
        setErrorMessage("OpenScholar received an unexpected response. Please try again.");
        return;
      }
      router.push(`/searches/${search.data.searchId}` as Route);
    } catch {
      setErrorMessage("Search is temporarily unavailable. Please try again.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="searchForm" noValidate onSubmit={submit}>
      {errorMessage !== null ? (
        <div
          className="errorSummary"
          id="search-error-summary"
          ref={errorSummaryRef}
          role="alert"
          tabIndex={-1}
        >
          <strong>{errorMessage}</strong>
          {violations.length > 0 ? (
            <ul>
              {violations.map((violation) => (
                <li key={violation}>{violation}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <label className="srOnly" htmlFor="research-query">
        Research topic
      </label>
      <div className="searchBar">
        <svg aria-hidden="true" viewBox="0 0 24 24" width="22" height="22">
          <circle cx="10.75" cy="10.75" r="6.75" />
          <path d="m16 16 4 4" />
        </svg>
        <input
          autoComplete="off"
          aria-describedby={
            invalidFields.includes("query") ? "search-error-summary" : undefined
          }
          aria-invalid={invalidFields.includes("query")}
          defaultValue={initialQuery}
          id="research-query"
          maxLength={500}
          minLength={3}
          name="query"
          placeholder="e.g. graph neural networks for drug discovery"
          required
          type="search"
        />
        <button className="button button--primary searchButton" disabled={pending}>
          {pending ? "Searching…" : "Search papers"}
        </button>
      </div>

      <details className="filterPanel">
        <summary>
          Refine search
          <span>Year, type, language, and citations</span>
        </summary>
        <div className="filterGrid">
          <div className="fieldGroup fieldGroup--years">
            <label htmlFor="year-from">From year</label>
            <input
              aria-describedby={
                invalidFields.includes("yearFrom")
                  ? "search-error-summary"
                  : undefined
              }
              aria-invalid={invalidFields.includes("yearFrom")}
              id="year-from"
              inputMode="numeric"
              max="9999"
              min="1000"
              name="yearFrom"
              placeholder="2018"
              type="number"
            />
          </div>
          <div className="fieldGroup fieldGroup--years">
            <label htmlFor="year-to">To year</label>
            <input
              aria-describedby={
                invalidFields.includes("yearTo")
                  ? "search-error-summary"
                  : undefined
              }
              aria-invalid={invalidFields.includes("yearTo")}
              id="year-to"
              inputMode="numeric"
              max="9999"
              min="1000"
              name="yearTo"
              placeholder="2026"
              type="number"
            />
          </div>
          <div className="fieldGroup">
            <label htmlFor="minimum-citations">Minimum citations</label>
            <input
              aria-describedby={
                invalidFields.includes("minimumCitations")
                  ? "search-error-summary"
                  : undefined
              }
              aria-invalid={invalidFields.includes("minimumCitations")}
              defaultValue="0"
              id="minimum-citations"
              inputMode="numeric"
              min="0"
              name="minimumCitations"
              type="number"
            />
          </div>
          <div className="fieldGroup">
            <label htmlFor="language">Language</label>
            <select
              aria-describedby={
                invalidFields.includes("languages")
                  ? "search-error-summary"
                  : undefined
              }
              aria-invalid={invalidFields.includes("languages")}
              defaultValue=""
              id="language"
              name="language"
            >
              <option value="">Any language</option>
              <option value="en">English</option>
              <option value="hi">Hindi</option>
              <option value="es">Spanish</option>
              <option value="fr">French</option>
              <option value="de">German</option>
              <option value="zh">Chinese</option>
            </select>
          </div>
        </div>

        <fieldset className="documentTypeFieldset">
          <legend>Document types</legend>
          <div className="checkboxGrid">
            {documentTypes.map((type) => (
              <label className="checkControl" key={type}>
                <input name="documentTypes" type="checkbox" value={type} />
                <span>{humanizeEnum(type)}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="optionRow">
          <label className="checkControl">
            <input name="openAccessOnly" type="checkbox" />
            <span>Show papers marked as open access</span>
          </label>
        </div>
      </details>

      <p aria-live="polite" className="formStatus">
        {pending
          ? "Searching trusted research sources."
          : "OpenScholar combines duplicate records and checks full-text access separately."}
      </p>
    </form>
  );
}
