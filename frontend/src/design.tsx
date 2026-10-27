/* eslint-disable react-refresh/only-export-components -- palette, provider
   and hook belong to one module (rule is an HMR hint) */
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { MantineProvider, createTheme } from "@mantine/core";
import { BRAND_INK, BRAND_ORANGE, BRAND_ORANGE_DARK, brandScale, type BrandScale } from "./palette";

/**
 * The application's default design, inspired by jfuchs.de's warm paper,
 * charcoal and orange palette.
 */
export interface DesignPalette {
  name: string;
  primary: string;   // primary/action color (Mantine primaryColor)
  headerBg: string;  // header/footer background
  accent: string;    // secondary/info accent
  onDark: string;    // link/text color on dark background
  paper: string;     // light application background
  surface: string;   // secondary surface
}

export const DESIGN: DesignPalette = {
  name: "Default Design",
  primary: BRAND_ORANGE_DARK,
  headerBg: BRAND_INK,
  accent: BRAND_ORANGE,
  onDark: BRAND_ORANGE,
  paper: "#F3F1EC",
  surface: "#E7E4DE",
};

const Ctx = createContext<{ design: DesignPalette }>({ design: DESIGN });

export const useDesign = () => useContext(Ctx);

/** Provides the Mantine theme built from the Default Design palette. */
export function DesignProvider({ children }: { children: ReactNode }) {
  const theme = useMemo(
    () =>
      createTheme({
        primaryColor: "brand",
        fontFamily: "'TheSans', system-ui, 'Segoe UI', sans-serif",
        colors: {
          brand: brandScale(DESIGN.primary),
          ink: brandScale(DESIGN.headerBg),
          accent: brandScale(DESIGN.accent),
        } as unknown as Record<string, BrandScale>,
        primaryShade: { light: 7, dark: 4 },
        autoContrast: true,
        luminanceThreshold: 0.18,
      }),
    []
  );
  return (
    <Ctx.Provider value={{ design: DESIGN }}>
      <MantineProvider
        theme={theme}
        defaultColorScheme="auto"
        cssVariablesResolver={() => ({
          variables: {},
          light: {
            "--mantine-color-body": DESIGN.paper,
            "--mantine-color-text": BRAND_INK,
            "--mantine-color-default": DESIGN.paper,
            "--mantine-color-default-border": "#77736A",
            "--mantine-color-dimmed": "#5B5E58",
            "--mantine-color-placeholder": "#5B5E58",
            "--mantine-color-anchor": "var(--mantine-color-brand-5)",
            "--mantine-color-error": "#C92A2A",
            "--mantine-color-success": "#06714F",
            "--mantine-color-brand-filled-hover": "var(--mantine-color-brand-8)",
            "--mantine-color-red-text": "#A61E2E",
            "--mantine-color-red-outline": "#A61E2E",
            "--mantine-color-green-text": "#1B6B32",
            "--mantine-color-orange-text": "#9A3412",
            "--mantine-color-orange-outline": "#9A3412",
            "--mantine-color-blue-text": "#1557A0",
            "--mantine-color-blue-outline": "#1557A0",
            "--mantine-color-teal-text": "#006B4F",
            "--mantine-color-lime-text": "#426300",
            "--mantine-color-yellow-text": "#7A4B00",
            "--mantine-color-cyan-outline": "#006B70",
            "--twx-health-good": "#1B6B32",
            "--twx-health-warning": "#8C4B00",
            "--twx-health-bad": "#A61E2E",
            "--twx-diagram-edge": "#495057",
            ...Object.fromEntries(
              ["dark", "gray", "red", "pink", "grape", "violet", "indigo", "blue", "cyan", "teal", "green", "lime", "yellow", "orange", "brand", "ink", "accent"]
                .map((color) => [`--mantine-color-${color}-light-color`, BRAND_INK])
            ),
          },
          dark: {
            "--mantine-color-body": BRAND_INK,
            "--mantine-color-text": DESIGN.paper,
            "--mantine-color-default": "#252724",
            "--mantine-color-default-border": "#70756F",
            "--mantine-color-dimmed": "#A9ADA8",
            "--mantine-color-placeholder": "#A9ADA8",
            "--mantine-color-anchor": "var(--mantine-color-brand-3)",
            "--mantine-color-error": "var(--mantine-color-red-4)",
            "--mantine-color-success": "var(--mantine-color-teal-4)",
            "--mantine-color-brand-filled-hover": "var(--mantine-color-brand-4)",
            "--mantine-color-red-text": "var(--mantine-color-red-4)",
            "--mantine-color-red-outline": "var(--mantine-color-red-0)",
            "--mantine-color-green-text": "var(--mantine-color-green-4)",
            "--mantine-color-orange-text": "var(--mantine-color-orange-4)",
            "--mantine-color-orange-outline": "var(--mantine-color-orange-0)",
            "--mantine-color-blue-text": "var(--mantine-color-blue-4)",
            "--mantine-color-blue-outline": "var(--mantine-color-blue-0)",
            "--mantine-color-teal-text": "var(--mantine-color-teal-4)",
            "--mantine-color-lime-text": "var(--mantine-color-lime-4)",
            "--mantine-color-yellow-text": "var(--mantine-color-yellow-4)",
            "--mantine-color-cyan-outline": "var(--mantine-color-cyan-0)",
            "--twx-health-good": "#69DB7C",
            "--twx-health-warning": "#FFD43B",
            "--twx-health-bad": "#FF8787",
            "--twx-diagram-edge": "#A9ADA8",
          },
        })}
      >
        {children}
      </MantineProvider>
    </Ctx.Provider>
  );
}
