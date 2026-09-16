import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { PaperDetails } from "@/features/papers/paper-details";
import {
  paperDetailsResponseFixture,
  relatedPapersResponseFixture,
} from "@/test/fixtures";

afterEach(cleanup);

describe("PaperDetails readable facts", () => {
  it.each([
    [1, "1 citation"],
    [null, "Citation count unavailable"],
  ])("formats a %s citation count for readers", (citationCount, label) => {
    const paper = paperDetailsResponseFixture();
    paper.citationCount = citationCount;
    render(
      <PaperDetails
        paper={paper}
        related={relatedPapersResponseFixture({ results: [] })}
      />,
    );
    expect(screen.getByText(label)).toBeVisible();
    expect(screen.queryByText("Not available citations")).not.toBeInTheDocument();
  });
});
