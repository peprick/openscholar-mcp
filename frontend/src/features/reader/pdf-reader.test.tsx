import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type {
  OnProgressParameters,
  PDFDocumentLoadingTask,
  PDFDocumentProxy,
  PDFPageProxy,
  RenderTask,
} from "pdfjs-dist";
import { afterEach, describe, expect, it, vi } from "vitest";

import { PdfReader } from "@/features/reader/pdf-reader";
import type { ReaderSource } from "@/features/reader/reader-source";
import { testIds } from "@/test/fixtures";

const loader = vi.hoisted(() => ({
  startPdfLoad: vi.fn(),
}));

vi.mock("@/features/reader/pdfjs-loader", () => loader);

const source: ReaderSource = {
  paperId: testIds.paper,
  locationId: testIds.location,
  pdfUrl: "https://repository.example.edu/items/paper-42.pdf",
  landingPageUrl: "https://repository.example.edu/items/paper-42",
  hostDomain: "repository.example.edu",
  source: "UNPAYWALL",
  versionType: "ACCEPTED_MANUSCRIPT",
  license: "CC-BY-4.0",
  verifiedAt: "2026-08-18T12:00:00Z",
};

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
}

function pdfLoadingTask(
  documentPromise: Promise<PDFDocumentProxy>,
): PDFDocumentLoadingTask {
  return {
    destroy: vi.fn().mockResolvedValue(undefined),
    onProgress: undefined,
    promise: documentPromise,
  } as unknown as PDFDocumentLoadingTask;
}

function successfulPdf(renderPromise: Promise<void> = Promise.resolve()) {
  const renderTask = {
    cancel: vi.fn(),
    promise: renderPromise,
  } as unknown as RenderTask;
  const page = {
    cleanup: vi.fn(),
    getViewport: vi.fn(({ scale }: { scale: number }) => ({
      height: 800 * scale,
      width: 600 * scale,
    })),
    render: vi.fn(() => renderTask),
  } as unknown as PDFPageProxy;
  const document = {
    getPage: vi.fn().mockResolvedValue(page),
    numPages: 3,
  } as unknown as PDFDocumentProxy;
  const task = pdfLoadingTask(Promise.resolve(document));
  return { document, page, renderTask, task };
}

afterEach(() => {
  cleanup();
  loader.startPdfLoad.mockReset();
});

describe("PdfReader", () => {
  it("loads the verified source and provides page and zoom controls", async () => {
    const user = userEvent.setup();
    const firstRender = deferred<void>();
    const pdf = successfulPdf(firstRender.promise);
    loader.startPdfLoad.mockResolvedValue(pdf.task);

    render(<PdfReader source={source} title="A verified research paper" />);

    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading verified PDF",
    );
    expect(loader.startPdfLoad).toHaveBeenCalledWith(source.pdfUrl);
    await waitFor(() => expect(pdf.page.render).toHaveBeenCalledOnce());
    expect(screen.getByText("Rendering…")).toBeVisible();
    expect(pdf.page.cleanup).not.toHaveBeenCalled();
    firstRender.resolve();
    await waitFor(() => expect(pdf.page.cleanup).toHaveBeenCalledOnce());
    expect(
      await screen.findByRole("img", {
        name: "A verified research paper, page 1 of 3",
      }),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("Page 1 of 3")).toBeVisible();
    expect(screen.getByText("CC-BY-4.0")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("Page 2 of 3")).toBeVisible();
    await waitFor(() => expect(pdf.document.getPage).toHaveBeenCalledWith(2));

    await user.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(screen.getByText("125%")).toBeVisible();

    const externalLink = screen.getByRole("link", {
      name: /Open verified PDF externally/,
    });
    expect(externalLink).toHaveAttribute("href", source.pdfUrl);
    expect(externalLink).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("shows a generic CORS-safe failure with retry and external fallback", async () => {
    const user = userEvent.setup();
    const loadFailure = deferred<PDFDocumentProxy>();
    const failedTask = pdfLoadingTask(loadFailure.promise);
    const recoveredPdf = successfulPdf();
    loader.startPdfLoad
      .mockResolvedValueOnce(failedTask)
      .mockResolvedValueOnce(recoveredPdf.task);

    render(<PdfReader source={source} title="A verified research paper" />);

    await waitFor(() =>
      expect(failedTask.onProgress).toEqual(expect.any(Function)),
    );
    loadFailure.reject(new TypeError("Failed to fetch"));
    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(failedTask.destroy).toHaveBeenCalledOnce());
    expect(alert).toHaveTextContent(
      "This PDF cannot be displayed inside OpenScholar.",
    );
    expect(alert).not.toHaveTextContent("Failed to fetch");
    const fallbackLink = within(alert).getByRole("link", {
      name: /Open verified PDF externally/,
    });
    expect(fallbackLink).toHaveAttribute("href", source.pdfUrl);
    expect(fallbackLink).toHaveAttribute("rel", "noopener noreferrer");

    const readerHeader = screen
      .getByRole("heading", { level: 1, name: "A verified research paper" })
      .closest("header");
    expect(readerHeader).not.toBeNull();
    expect(
      within(readerHeader!).getByRole("link", {
        name: /Open verified PDF externally/,
      }),
    ).toHaveAttribute("href", source.pdfUrl);

    await user.click(screen.getByRole("button", { name: "Retry reader" }));
    expect(
      await screen.findByRole("img", {
        name: "A verified research paper, page 1 of 3",
      }),
    ).toBeVisible();
    expect(loader.startPdfLoad).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(recoveredPdf.page.cleanup).toHaveBeenCalled());
  });

  it("recovers when rendering a loaded page fails", async () => {
    const user = userEvent.setup();
    const renderFailure = deferred<void>();
    const brokenPdf = successfulPdf(renderFailure.promise);
    const recoveredPdf = successfulPdf();
    loader.startPdfLoad
      .mockResolvedValueOnce(brokenPdf.task)
      .mockResolvedValueOnce(recoveredPdf.task);

    render(<PdfReader source={source} title="A verified research paper" />);

    await waitFor(() => expect(brokenPdf.page.render).toHaveBeenCalledOnce());
    renderFailure.reject(new Error("Canvas rendering failed"));

    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(brokenPdf.task.destroy).toHaveBeenCalledOnce());
    expect(alert).toHaveTextContent(
      "This PDF cannot be displayed inside OpenScholar.",
    );
    expect(alert).not.toHaveTextContent("Canvas rendering failed");
    expect(
      within(alert).getByRole("link", {
        name: /Open verified PDF externally/,
      }),
    ).toHaveAttribute("href", source.pdfUrl);

    await user.click(within(alert).getByRole("button", { name: "Retry reader" }));
    expect(
      await screen.findByRole("img", {
        name: "A verified research paper, page 1 of 3",
      }),
    ).toBeVisible();
    await waitFor(() => expect(recoveredPdf.page.cleanup).toHaveBeenCalled());
    expect(loader.startPdfLoad).toHaveBeenCalledTimes(2);
  });

  it("destroys the PDF.js loading task on unmount", async () => {
    const pdf = successfulPdf();
    loader.startPdfLoad.mockResolvedValue(pdf.task);

    const view = render(
      <PdfReader source={source} title="A verified research paper" />,
    );
    await screen.findByRole("img");
    view.unmount();

    await waitFor(() => expect(pdf.task.destroy).toHaveBeenCalledOnce());
  });

  it("destroys a loading task that arrives after unmount", async () => {
    const pendingStart = deferred<PDFDocumentLoadingTask>();
    const pdf = successfulPdf();
    loader.startPdfLoad.mockReturnValue(pendingStart.promise);

    const view = render(
      <PdfReader source={source} title="A verified research paper" />,
    );
    expect(loader.startPdfLoad).toHaveBeenCalledWith(source.pdfUrl);
    view.unmount();

    pendingStart.resolve(pdf.task);
    await waitFor(() => expect(pdf.task.destroy).toHaveBeenCalledOnce());
    expect(pdf.document.getPage).not.toHaveBeenCalled();
  });

  it("aborts a PDF when reported bytes exceed the reader ceiling", async () => {
    const pendingDocument = deferred<PDFDocumentProxy>();
    const task = pdfLoadingTask(pendingDocument.promise);
    loader.startPdfLoad.mockResolvedValue(task);

    render(<PdfReader source={source} title="A verified research paper" />);
    await waitFor(() => expect(task.onProgress).toEqual(expect.any(Function)));

    await act(async () => {
      task.onProgress?.({
        loaded: 75 * 1024 * 1024 + 1,
        total: 75 * 1024 * 1024 + 1,
      } as OnProgressParameters);
    });

    expect(
      await screen.findByRole("heading", {
        name: "This PDF cannot be displayed inside OpenScholar.",
      }),
    ).toBeVisible();
    expect(task.destroy).toHaveBeenCalledOnce();
  });

  it("starts a clean session when a verified source changes", async () => {
    const firstPdf = successfulPdf();
    const secondPdf = successfulPdf();
    const refreshedSource = {
      ...source,
      pdfUrl: "https://repository.example.edu/items/paper-42-v2.pdf",
      verifiedAt: "2026-08-18T13:00:00Z",
    };
    loader.startPdfLoad
      .mockResolvedValueOnce(firstPdf.task)
      .mockResolvedValueOnce(secondPdf.task);

    const view = render(
      <PdfReader source={source} title="A verified research paper" />,
    );
    await waitFor(() => expect(firstPdf.page.cleanup).toHaveBeenCalledOnce());

    view.rerender(
      <PdfReader source={refreshedSource} title="A verified research paper" />,
    );

    await waitFor(() =>
      expect(loader.startPdfLoad).toHaveBeenLastCalledWith(
        refreshedSource.pdfUrl,
      ),
    );
    await waitFor(() => expect(firstPdf.task.destroy).toHaveBeenCalledOnce());
    await waitFor(() => expect(secondPdf.page.cleanup).toHaveBeenCalledOnce());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("exposes bounded extracted page text to assistive technology", async () => {
    const pdf = successfulPdf();
    const textReader = {
      cancel: vi.fn().mockResolvedValue(undefined),
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: { items: [{ str: "Accessible research page text" }] },
        })
        .mockResolvedValueOnce({ done: true }),
      releaseLock: vi.fn(),
    };
    Object.assign(pdf.page, {
      streamTextContent: vi.fn(() => ({ getReader: () => textReader })),
    });
    loader.startPdfLoad.mockResolvedValue(pdf.task);

    render(<PdfReader source={source} title="A verified research paper" />);

    const accessiblePage = await screen.findByRole("region", {
      name: "Accessible text for page 1",
    });
    expect(accessiblePage).toHaveTextContent("Accessible research page text");
    expect(
      document.querySelector("canvas[aria-hidden='true']"),
    ).toBeInTheDocument();
    expect(textReader.cancel).not.toHaveBeenCalled();
    expect(textReader.releaseLock).toHaveBeenCalledOnce();
  });

  it("cancels incremental text extraction at the character ceiling", async () => {
    const pdf = successfulPdf();
    const textReader = {
      cancel: vi.fn().mockResolvedValue(undefined),
      read: vi.fn().mockResolvedValue({
        done: false,
        value: { items: [{ str: "x".repeat(40_001) }] },
      }),
      releaseLock: vi.fn(),
    };
    Object.assign(pdf.page, {
      streamTextContent: vi.fn(() => ({ getReader: () => textReader })),
    });
    loader.startPdfLoad.mockResolvedValue(pdf.task);

    render(<PdfReader source={source} title="A verified research paper" />);

    const accessiblePage = await screen.findByRole("region", {
      name: "Accessible text for page 1",
    });
    expect(accessiblePage.querySelector("p")?.textContent).toHaveLength(40_000);
    expect(textReader.read).toHaveBeenCalledOnce();
    expect(textReader.cancel).toHaveBeenCalledOnce();
    expect(textReader.releaseLock).toHaveBeenCalledOnce();
  });

  it("cancels an in-flight page render before rendering the next page", async () => {
    const user = userEvent.setup();
    const firstRender = deferred<void>();
    const firstRenderTask = {
      cancel: vi.fn(),
      promise: firstRender.promise,
    } as unknown as RenderTask;
    const secondRenderTask = {
      cancel: vi.fn(),
      promise: Promise.resolve(),
    } as unknown as RenderTask;
    const firstPage = {
      cleanup: vi.fn(),
      getViewport: vi.fn(({ scale }: { scale: number }) => ({
        height: 800 * scale,
        width: 600 * scale,
      })),
      render: vi.fn(() => firstRenderTask),
    } as unknown as PDFPageProxy;
    const secondPage = {
      cleanup: vi.fn(),
      getViewport: vi.fn(({ scale }: { scale: number }) => ({
        height: 800 * scale,
        width: 600 * scale,
      })),
      render: vi.fn(() => secondRenderTask),
    } as unknown as PDFPageProxy;
    const document = {
      getPage: vi.fn((pageNumber: number) =>
        Promise.resolve(pageNumber === 1 ? firstPage : secondPage),
      ),
      numPages: 2,
    } as unknown as PDFDocumentProxy;
    loader.startPdfLoad.mockResolvedValue(
      pdfLoadingTask(Promise.resolve(document)),
    );

    render(<PdfReader source={source} title="A verified research paper" />);
    await waitFor(() => expect(firstPage.render).toHaveBeenCalledOnce());

    await user.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => expect(firstRenderTask.cancel).toHaveBeenCalledOnce());
    await waitFor(() => expect(document.getPage).toHaveBeenCalledWith(2));
    await waitFor(() => expect(secondPage.cleanup).toHaveBeenCalledOnce());
    firstRender.resolve();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
