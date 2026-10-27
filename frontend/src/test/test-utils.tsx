import type { ReactNode } from "react";
import { render } from "@testing-library/react";
import { DesignProvider } from "../design";
import { LangProvider } from "../i18n";

export function renderWithProviders(ui: ReactNode) {
  return render(
    <DesignProvider>
      <LangProvider>{ui}</LangProvider>
    </DesignProvider>,
  );
}
