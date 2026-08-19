import type { PDFDocumentLoadingTask } from "pdfjs-dist";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  PDFJS_VERSION,
  startPdfLoad,
} from "@/features/reader/pdfjs-loader";

const pdfjs = vi.hoisted(() => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
  version: "6.2.108",
}));

vi.mock("pdfjs-dist/legacy/build/pdf.mjs", () => pdfjs);

afterEach(() => {
  pdfjs.getDocument.mockReset();
  pdfjs.GlobalWorkerOptions.workerSrc = "";
  pdfjs.version = PDFJS_VERSION;
});

describe("startPdfLoad", () => {
  it("uses pinned same-origin assets and bounded credential-free loading", async () => {
    const task = {
      promise: Promise.resolve(),
    } as unknown as PDFDocumentLoadingTask;
    pdfjs.getDocument.mockReturnValue(task);
    const pdfUrl = "https://repository.example.edu/items/paper-42.pdf";

    await expect(startPdfLoad(pdfUrl)).resolves.toBe(task);

    expect(pdfjs.GlobalWorkerOptions.workerSrc).toBe(
      `/pdfjs/${PDFJS_VERSION}/pdf.worker.min.mjs`,
    );
    expect(pdfjs.getDocument).toHaveBeenCalledWith({
      url: pdfUrl,
      cMapUrl: `/pdfjs/${PDFJS_VERSION}/cmaps/`,
      cMapPacked: true,
      canvasMaxAreaInBytes: 64 * 1024 * 1024,
      disableAutoFetch: true,
      disableStream: true,
      iccUrl: `/pdfjs/${PDFJS_VERSION}/iccs/`,
      maxImageSize: 25_000_000,
      standardFontDataUrl: `/pdfjs/${PDFJS_VERSION}/standard_fonts/`,
      stopAtErrors: false,
      useWorkerFetch: true,
      wasmUrl: `/pdfjs/${PDFJS_VERSION}/wasm/`,
      withCredentials: false,
    });
  });

  it("refuses to load when the PDF.js API and static assets differ", async () => {
    pdfjs.version = "6.2.999";

    await expect(
      startPdfLoad("https://repository.example.edu/paper.pdf"),
    ).rejects.toThrow("PDF.js API and static asset versions do not match.");
    expect(pdfjs.getDocument).not.toHaveBeenCalled();
    expect(pdfjs.GlobalWorkerOptions.workerSrc).toBe("");
  });
});
