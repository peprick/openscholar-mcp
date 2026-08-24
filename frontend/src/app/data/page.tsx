import type { Metadata } from "next";

import { PrivacyCenter } from "@/features/privacy/privacy-center";

export const metadata: Metadata = {
  title: "Your data & privacy",
  description:
    "Download or delete the search and library data associated with your OpenScholar workspace.",
};

export default function DataPage(): React.JSX.Element {
  return (
    <main className="shell page dataPage" id="main-content">
      <header className="dataIntro">
        <span className="eyebrow">Privacy and control</span>
        <h1>Your data &amp; privacy</h1>
        <p>
          OpenScholar keeps your searches and research library together so it can
          reuse results and remember your reading work. You can take a copy of
          that information or remove it from this OpenScholar workspace.
        </p>
      </header>
      <PrivacyCenter />
    </main>
  );
}
