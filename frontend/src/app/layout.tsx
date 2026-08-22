import type { Metadata } from "next";
import type { Route } from "next";
import Link from "next/link";

import { Brand } from "@/shared/ui/brand";
import { AuthNavigation } from "@/shared/ui/auth-navigation";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "OpenScholar — Research with sources",
    template: "%s — OpenScholar",
  },
  description:
    "Discover scholarly work, see where paper details come from, and find checked free full-text links.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>): React.JSX.Element {
  return (
    <html data-scroll-behavior="smooth" lang="en">
      <body>
        <a className="skipLink" href="#main-content">
          Skip to main content
        </a>
        <header className="siteHeader">
          <div className="shell headerInner">
            <Brand />
            <nav aria-label="Primary navigation">
              <Link href="/">Search research</Link>
              <Link href={"/library" as Route}>My library</Link>
              <AuthNavigation />
            </nav>
          </div>
        </header>
        {children}
        <footer className="siteFooter">
          <div className="shell footerInner">
            <p>
              OpenScholar shows where paper details come from and checks free
              full-text links. It never bypasses publisher access controls.
            </p>
            <p>
              Save papers into collections, export citations, and read checked
              open versions without storing source PDFs.
            </p>
          </div>
        </footer>
      </body>
    </html>
  );
}
