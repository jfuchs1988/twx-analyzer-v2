import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { renderWithProviders } from "@/test/test-utils";

vi.mock("@/features/snapshots/SnapshotsPage", () => ({
  default: () => <div data-testid="process-apps-page" />,
}));
vi.mock("@/features/toolkits/ToolkitPage", () => ({
  default: () => <div data-testid="toolkits-page" />,
}));
vi.mock("@/features/dashboard/DashboardPage", () => ({
  default: () => <div data-testid="dashboard-page" />,
}));
vi.mock("@/features/search/SearchPage", () => ({
  default: () => <div data-testid="search-page" />,
}));
vi.mock("@/features/compare/ComparePage", () => ({
  default: () => <div data-testid="compare-page" />,
}));
vi.mock("@/features/settings/SettingsPage", () => ({
  default: () => <div data-testid="settings-page" />,
}));
vi.mock("@/features/settings/RuleSettingsPage", () => ({
  default: () => <div data-testid="rules-page" />,
}));
vi.mock("@/features/snapshots/SnapshotDetail", () => ({
  default: () => <div data-testid="snapshot-detail" />,
}));
vi.mock("@/shared/components/IngestBanner", () => ({ default: () => null }));
vi.mock("@/shared/components/Footer", () => ({ default: () => null }));

describe("Products navigation", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
    localStorage.clear();
  });

  it("groups process apps and toolkits under Produkte in German", () => {
    localStorage.setItem("twxav2.lang", "de");
    renderWithProviders(<App />);

    expect(screen.getByText("Produkte")).toBeInTheDocument();
    expect(screen.getByText("Prozess-Apps")).toBeInTheDocument();
    expect(screen.getByText("Toolkits")).toBeInTheDocument();
  });

  it("opens the selected product page", async () => {
    localStorage.setItem("twxav2.lang", "de");
    const user = userEvent.setup();
    renderWithProviders(<App />);

    await user.click(screen.getByText("Toolkits"));

    expect(screen.getByTestId("toolkits-page")).toBeInTheDocument();
  });

  it("uses Products in English", () => {
    localStorage.setItem("twxav2.lang", "en");
    renderWithProviders(<App />);

    expect(screen.getByText("Products")).toBeInTheDocument();
    expect(screen.getByText("Process apps")).toBeInTheDocument();
    expect(screen.getByText("Toolkits")).toBeInTheDocument();
  });
});
