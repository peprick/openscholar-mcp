import type { PDFDocumentLoadingTask } from "pdfjs-dist";

export const PDFJS_VERSION = "6.2.108";
const PDFJS_ASSET_BASE = `/pdfjs/${PDFJS_VERSION}`;

export async function startPdfLoad(
  pdfUrl: string,
): Promise<PDFDocumentLoadingTask> {
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  if (pdfjs.version !== PDFJS_VERSION) {
    throw new Error("PDF.js API and static asset versions do not match.");
  }

  pdfjs.GlobalWorkerOptions.workerSrc = `${PDFJS_ASSET_BASE}/pdf.worker.min.mjs`;
  return pdfjs.getDocument({
    url: pdfUrl,
    cMapUrl: `${PDFJS_ASSET_BASE}/cmaps/`,
    cMapPacked: true,
    canvasMaxAreaInBytes: 64 * 1024 * 1024,
    disableAutoFetch: true,
    disableStream: true,
    iccUrl: `${PDFJS_ASSET_BASE}/iccs/`,
    maxImageSize: 25_000_000,
    standardFontDataUrl: `${PDFJS_ASSET_BASE}/standard_fonts/`,
    stopAtErrors: false,
    useWorkerFetch: true,
    wasmUrl: `${PDFJS_ASSET_BASE}/wasm/`,
    withCredentials: false,
  });
}
