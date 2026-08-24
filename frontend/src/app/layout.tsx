import type { Metadata, Viewport } from "next";
import type { Route } from "next";
import Link from "next/link";

import { ServiceWorkerRegistration } from "@/pwa/service-worker-registration";
import { ConnectivityProvider } from "@/shared/connectivity/connectivity-context";
import { Brand } from "@/shared/ui/brand";
import { AuthNavigation } from "@/shared/ui/auth-navigation";
import { ConnectivityStatus } from "@/shared/ui/connectivity-status";

import "./globals.css";

export const metadata: Metadata = {
  applicationName: "OpenScholar",
  title: {
    default: "OpenScholar — Research with sources",
    template: "%s — OpenScholar",
  },
  description:
    "Discover scholarly work, see where paper details come from, and find checked free full-text links.",
  manifest: "/manifest.webmanifest",
  icons: {
    apple: [
      {
        url: "/apple-touch-icon.png",
        sizes: "180x180",
        type: "image/png",
      },
    ],
    icon: [{ url: "/icon.svg", type: "image/svg+xml" }],
  },
};

export const viewport: Viewport = {
  colorScheme: "light",
  themeColor: "#155c47",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>): React.JSX.Element {
  return (
    <html data-scroll-behavior="smooth" lang="en">
      <body>
        <ConnectivityProvider>
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
          <ConnectivityStatus id="app-connectivity-status" />
          {children}
          <footer className="siteFooter">
            <div className="shell footerInner">
              <p>
                OpenScholar shows where paper details come from and checks free
                full-text links. It never bypasses publisher access controls.
              </p>
              <div className="footerActions">
                <p>
                  Save papers into collections, export citations, and read checked
                  open versions without storing source PDFs.
                </p>
                <Link className="footerDataLink" href={"/data" as Route}>
                  Your data &amp; privacy <span aria-hidden="true">→</span>
                </Link>
              </div>
            </div>
          </footer>
        </ConnectivityProvider>
        <ServiceWorkerRegistration
          enabled={
            process.env.NODE_ENV === "production" ||
            process.env.OPENSCHOLAR_E2E_PWA === "true"
          }
        />
      </body>
    </html>
  );
}
