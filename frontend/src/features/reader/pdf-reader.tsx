"use client";

import type {
  OnProgressParameters,
  PDFDocumentLoadingTask,
  PDFDocumentProxy,
  PDFPageProxy,
  RenderTask,
} from "pdfjs-dist";
import { useEffect, useRef, useState } from "react";

import { startPdfLoad } from "@/features/reader/pdfjs-loader";
import type { ReaderSource } from "@/features/reader/reader-source";
import { formatInstant, humanizeEnum } from "@/shared/formatting/display";
import { ExternalLink } from "@/shared/ui/external-link";

const MIN_ZOOM = 0.75;
const MAX_ZOOM = 2;
const ZOOM_STEP = 0.25;
const MAX_CANVAS_PIXELS = 16_000_000;
const MAX_CANVAS_SIDE = 8_192;
const MAX_CSS_SIDE = 5_000;
const MAX_PDF_BYTES = 75 * 1024 * 1024;
const MAX_ACCESSIBLE_TEXT_CHARS = 40_000;
const MAX_ACCESSIBLE_TEXT_ITEMS = 5_000;
const PDF_LOAD_TIMEOUT_MS = 45_000;
const PAGE_RENDER_TIMEOUT_MS = 20_000;

type RenderedPage = {
  accessibleText: string | null;
  pageNumber: number;
  zoom: number;
};

function boundedViewportScale(
  width: number,
  height: number,
  requestedScale: number,
): number {
  const largestSide = Math.max(width, height);
  return largestSide * requestedScale <= MAX_CSS_SIDE
    ? requestedScale
    : MAX_CSS_SIDE / largestSide;
}

function canvasOutputScale(width: number, height: number): number {
  const deviceScale = Math.min(window.devicePixelRatio || 1, 2);
  const pixelScale = Math.sqrt(MAX_CANVAS_PIXELS / Math.max(width * height, 1));
  const sideScale = Math.min(
    MAX_CANVAS_SIDE / Math.max(width, 1),
    MAX_CANVAS_SIDE / Math.max(height, 1),
  );
  return Math.max(0.1, Math.min(deviceScale, pixelScale, sideScale));
}

function ignoreCleanupFailure(promise: Promise<void>): void {
  void promise.catch(() => undefined);
}

async function accessiblePageText(page: PDFPageProxy): Promise<string | null> {
  if (typeof page.streamTextContent !== "function") {
    return null;
  }

  const reader = page.streamTextContent().getReader();
  let characterCount = 0;
  let itemCount = 0;
  let completed = false;
  let failed = false;
  const fragments: string[] = [];
  try {
    while (
      characterCount < MAX_ACCESSIBLE_TEXT_CHARS &&
      itemCount < MAX_ACCESSIBLE_TEXT_ITEMS
    ) {
      const chunk = await reader.read();
      if (chunk.done) {
        completed = true;
        break;
      }
      for (const item of chunk.value.items) {
        itemCount += 1;
        if (itemCount > MAX_ACCESSIBLE_TEXT_ITEMS) {
          break;
        }
        if (
          typeof item !== "object" ||
          item === null ||
          !("str" in item) ||
          typeof item.str !== "string"
        ) {
          continue;
        }
        const remaining = MAX_ACCESSIBLE_TEXT_CHARS - characterCount;
        if (remaining <= 0) {
          break;
        }
        const fragment = item.str.slice(0, remaining).trim();
        if (fragment === "") {
          continue;
        }
        fragments.push(fragment);
        characterCount += fragment.length + 1;
      }
    }
  } catch {
    failed = true;
  } finally {
    if (!completed) {
      await reader.cancel().catch(() => undefined);
    }
    reader.releaseLock();
  }

  if (failed) {
    return null;
  }
  const text = fragments.join(" ").replace(/\s+/g, " ").trim();
  return text === "" ? null : text;
}

export function PdfReader(props: {
  source: ReaderSource;
  title: string;
}): React.JSX.Element {
  return (
    <PdfReaderSession
      key={`${props.source.paperId}:${props.source.locationId}:${props.source.verifiedAt}:${props.source.pdfUrl}`}
      {...props}
    />
  );
}

function PdfReaderSession({
  source,
  title,
}: {
  source: ReaderSource;
  title: string;
}): React.JSX.Element {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const destroyLoadingTaskRef = useRef<(() => void) | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [pageCount, setPageCount] = useState(0);
  const [pageNumber, setPageNumber] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [loadPercent, setLoadPercent] = useState<number | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [renderError, setRenderError] = useState(false);
  const [renderedPage, setRenderedPage] = useState<RenderedPage | null>(null);

  useEffect(() => {
    let active = true;
    let loadingTask: PDFDocumentLoadingTask | null = null;
    let destroyed = false;
    let loadFailed = false;

    function destroyLoadingTask(): void {
      if (loadingTask === null || destroyed) {
        return;
      }
      destroyed = true;
      ignoreCleanupFailure(loadingTask.destroy());
    }

    function failLoad(): void {
      if (!active || loadFailed) {
        return;
      }
      loadFailed = true;
      setLoadError(true);
      destroyLoadingTask();
    }

    destroyLoadingTaskRef.current = destroyLoadingTask;
    const loadTimeout = window.setTimeout(failLoad, PDF_LOAD_TIMEOUT_MS);

    void (async () => {
      try {
        loadingTask = await startPdfLoad(source.pdfUrl);
        if (!active || loadFailed) {
          destroyLoadingTask();
          return;
        }
        loadingTask.onProgress = (progress: OnProgressParameters) => {
          if (
            progress.loaded > MAX_PDF_BYTES ||
            progress.total > MAX_PDF_BYTES
          ) {
            failLoad();
          } else if (active && Number.isFinite(progress.percent)) {
            setLoadPercent(Math.max(0, Math.min(progress.percent, 100)));
          }
        };
        const loadedDocument = await loadingTask.promise;
        if (!active || loadFailed) {
          destroyLoadingTask();
          return;
        }
        window.clearTimeout(loadTimeout);
        setDocument(loadedDocument);
        setPageCount(loadedDocument.numPages);
      } catch {
        window.clearTimeout(loadTimeout);
        if (active) {
          setLoadError(true);
        }
        destroyLoadingTask();
      }
    })();

    return () => {
      active = false;
      window.clearTimeout(loadTimeout);
      destroyLoadingTask();
      if (destroyLoadingTaskRef.current === destroyLoadingTask) {
        destroyLoadingTaskRef.current = null;
      }
    };
  }, [attempt, source.pdfUrl]);

  useEffect(() => {
    if (document === null || canvasRef.current === null) {
      return;
    }

    let active = true;
    let page: PDFPageProxy | null = null;
    let renderTask: RenderTask | null = null;
    let timedOut = false;
    const renderTimeout = window.setTimeout(() => {
      timedOut = true;
      renderTask?.cancel();
      if (active) {
        setRenderError(true);
        destroyLoadingTaskRef.current?.();
      }
    }, PAGE_RENDER_TIMEOUT_MS);

    void (async () => {
      try {
        page = await document.getPage(pageNumber);
        if (!active || timedOut || canvasRef.current === null) {
          return;
        }
        const baseViewport = page.getViewport({ scale: 1 });
        const viewport = page.getViewport({
          scale: boundedViewportScale(
            baseViewport.width,
            baseViewport.height,
            zoom,
          ),
        });
        const outputScale = canvasOutputScale(viewport.width, viewport.height);
        const canvas = canvasRef.current;
        canvas.width = Math.floor(viewport.width * outputScale);
        canvas.height = Math.floor(viewport.height * outputScale);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;

        renderTask = page.render({
          canvas,
          transform:
            outputScale === 1
              ? undefined
              : [outputScale, 0, 0, outputScale, 0, 0],
          viewport,
        });
        await renderTask.promise;
        if (!active || timedOut) {
          return;
        }
        const accessibleText = await accessiblePageText(page);
        if (active && !timedOut) {
          setRenderedPage({ accessibleText, pageNumber, zoom });
        }
      } catch {
        if (active && !timedOut) {
          setRenderError(true);
          destroyLoadingTaskRef.current?.();
        }
      } finally {
        window.clearTimeout(renderTimeout);
        page?.cleanup();
      }
    })();

    return () => {
      active = false;
      window.clearTimeout(renderTimeout);
      renderTask?.cancel();
    };
  }, [document, pageNumber, zoom]);

  const readerFailed = loadError || renderError;
  const loading = document === null && !loadError;
  const renderedPageMatches =
    renderedPage?.pageNumber === pageNumber && renderedPage.zoom === zoom;
  const rendering = document !== null && !readerFailed && !renderedPageMatches;

  function changePage(direction: -1 | 1): void {
    setRenderedPage(null);
    setPageNumber((current) =>
      Math.max(1, Math.min(pageCount, current + direction)),
    );
  }

  function changeZoom(nextZoom: number): void {
    setRenderedPage(null);
    setZoom(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, nextZoom)));
  }

  function retryReader(): void {
    destroyLoadingTaskRef.current?.();
    setDocument(null);
    setPageCount(0);
    setPageNumber(1);
    setZoom(1);
    setLoadPercent(null);
    setLoadError(false);
    setRenderError(false);
    setRenderedPage(null);
    setAttempt((current) => current + 1);
  }

  return (
    <section className="pdfReader" aria-labelledby="reader-heading">
      <header className="readerHeader">
        <div>
          <span className="eyebrow">Verified source reader</span>
          <h1 id="reader-heading">{title}</h1>
          <p>
            {source.hostDomain} · {humanizeEnum(source.versionType)} · {source.source}
          </p>
        </div>
        <div className="readerExternalActions">
          <ExternalLink className="button button--primary" href={source.pdfUrl}>
            Open verified PDF externally
          </ExternalLink>
          {source.landingPageUrl !== null &&
          source.landingPageUrl !== source.pdfUrl ? (
            <ExternalLink
              className="button button--ghost"
              href={source.landingPageUrl}
            >
              View source page
            </ExternalLink>
          ) : null}
        </div>
      </header>

      <dl className="readerProvenance">
        <div>
          <dt>Verified</dt>
          <dd>{formatInstant(source.verifiedAt)}</dd>
        </div>
        <div>
          <dt>Licence</dt>
          <dd>{source.license ?? "Not reported"}</dd>
        </div>
        <div>
          <dt>Handling</dt>
          <dd>Direct, link-only browser load</dd>
        </div>
      </dl>

      <div className="readerControls" aria-label="PDF controls">
        <button
          className="button button--ghost"
          disabled={document === null || pageNumber <= 1 || readerFailed}
          onClick={() => changePage(-1)}
          type="button"
        >
          Previous
        </button>
        <span aria-live="polite" className="readerPageStatus">
          {renderedPageMatches
            ? `Page ${pageNumber} of ${pageCount}`
            : document === null
              ? "Page — of —"
              : "Rendering…"}
        </span>
        <button
          className="button button--ghost"
          disabled={
            document === null || pageNumber >= pageCount || readerFailed
          }
          onClick={() => changePage(1)}
          type="button"
        >
          Next
        </button>
        <span aria-hidden="true" className="readerControlDivider" />
        <button
          aria-label="Zoom out"
          className="button button--ghost readerZoomButton"
          disabled={document === null || zoom <= MIN_ZOOM || readerFailed}
          onClick={() => changeZoom(zoom - ZOOM_STEP)}
          type="button"
        >
          −
        </button>
        <span aria-live="polite" className="readerZoomStatus">
          {Math.round(zoom * 100)}%
        </span>
        <button
          aria-label="Zoom in"
          className="button button--ghost readerZoomButton"
          disabled={document === null || zoom >= MAX_ZOOM || readerFailed}
          onClick={() => changeZoom(zoom + ZOOM_STEP)}
          type="button"
        >
          +
        </button>
        <button
          className="button button--ghost"
          disabled={document === null || zoom === 1 || readerFailed}
          onClick={() => changeZoom(1)}
          type="button"
        >
          Reset zoom
        </button>
      </div>

      <div
        aria-busy={loading || rendering}
        className="readerViewport"
        data-reader-state={
          readerFailed
            ? "failed"
            : loading
              ? "loading"
              : rendering
                ? "rendering"
                : "ready"
        }
      >
        {loading ? (
          <div className="readerLoading" role="status">
            <span aria-hidden="true" className="readerSpinner" />
            <strong>Loading verified PDF…</strong>
            <span>
              {loadPercent === null
                ? "Waiting for the source"
                : `${Math.round(loadPercent)}% received`}
            </span>
          </div>
        ) : null}

        {rendering ? (
          <div className="readerLoading" role="status">
            <span aria-hidden="true" className="readerSpinner" />
            <strong>Rendering verified page…</strong>
            <span>The preview will appear after this page is ready.</span>
          </div>
        ) : null}

        {readerFailed ? (
          <div className="readerFailure" role="alert">
            <span className="eyebrow">External fallback ready</span>
            <h2>This PDF cannot be displayed inside OpenScholar.</h2>
            <p>
              The source may block cross-origin reading, require browser access,
              or have changed since verification. Open the same verified URL
              externally, or retry the direct browser load.
            </p>
            <div className="buttonGroup">
              <button
                className="button button--ghost"
                onClick={retryReader}
                type="button"
              >
                Retry reader
              </button>
              <ExternalLink
                className="button button--primary"
                href={source.pdfUrl}
              >
                Open verified PDF externally
              </ExternalLink>
            </div>
          </div>
        ) : null}

        <canvas
          aria-label={`${title}, page ${pageNumber} of ${pageCount || "unknown"}`}
          aria-hidden={
            renderedPageMatches && renderedPage.accessibleText !== null
              ? true
              : undefined
          }
          className="readerCanvas"
          hidden={!renderedPageMatches || readerFailed}
          ref={canvasRef}
          role="img"
        />
      </div>

      {renderedPageMatches && renderedPage.accessibleText !== null ? (
        <section
          aria-label={`Accessible text for page ${pageNumber}`}
          className="readerAccessibleText"
        >
          <h2>Page {pageNumber} text</h2>
          <p>{renderedPage.accessibleText}</p>
        </section>
      ) : null}

      <p className="readerPolicyNote">
        PDF.js requests this document directly from the verified host. OpenScholar
        does not proxy, persist, or redistribute its bytes. A bounded page-text
        representation is exposed to assistive technology when extraction is
        available; use an external PDF reader for selectable visual text, search,
        or document-native controls.
      </p>
    </section>
  );
}
