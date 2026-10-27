import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RuleSettingsPage from "./RuleSettingsPage";
import { api } from "@/shared/api";
import { renderWithProviders } from "@/test/test-utils";

vi.mock("@/shared/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/shared/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      snapshots: vi.fn(),
      rules: vi.fn(),
      ruleSettingsDefaults: vi.fn(),
      ruleSettings: vi.fn(),
      saveRuleSettings: vi.fn(),
    },
  };
});

describe("RuleSettingsPage", () => {
  beforeEach(() => {
    vi.mocked(api.snapshots).mockResolvedValue([]);
    vi.mocked(api.rules).mockResolvedValue([{
      id: "R-JS-001",
      title: "R001 Unsafe script",
      category: "Scripts",
      severity: "MAJOR",
      impact: 2,
      enabled: true,
      customized: false,
      rationale: "Synthetic test rule",
      guidance: "Review the script",
    }]);
    vi.mocked(api.ruleSettingsDefaults).mockResolvedValue({});
    vi.mocked(api.ruleSettings).mockResolvedValue({});
    vi.mocked(api.saveRuleSettings).mockResolvedValue(undefined);
  });

  it("saves a changed rule enablement override", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RuleSettingsPage />);

    const enabled = await screen.findByRole("switch");
    expect(enabled).toBeChecked();
    await user.click(enabled);
    const saveButtons = screen.getAllByRole("button", { name: /^Save$/ });
    await user.click(saveButtons[0]);

    expect(api.saveRuleSettings).toHaveBeenCalledWith({
      overrides: { "R-JS-001": { enabled: false } },
      thresholds: {},
    }, undefined);
    expect(await screen.findByText(/Saved\./)).toBeInTheDocument();
  });
});
