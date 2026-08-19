import type { Metadata } from "next";
import type { Route } from "next";
import Link from "next/link";

import { Brand } from "@/shared/ui/brand";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "OpenScholar — Research with provenance",
    template: "%s — OpenScholar",
  },
  description:
    "Discover scholarly work, inspect its provenance, and find independently verified legal versions.",
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
              <a
                href="https://modelcontextprotocol.io"
                rel="noopener noreferrer"
                target="_blank"
              >
                About MCP<span className="srOnly"> (opens in a new tab)</span>
              </a>
            </nav>
          </div>
        </header>
        {children}
        <footer className="siteFooter">
          <div className="shell footerInner">
            <p>
              OpenScholar surfaces legal links and provenance. It never bypasses
              publisher access controls.
            </p>
            <p>
              Built with Spring Boot, PostgreSQL, and Next.js. Collections,
              citation exports, and the legal-link reader are available.
            </p>
          </div>
        </footer>
      </body>
    </html>
  );
}
