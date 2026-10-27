/** Fuchs IT-Consulting brand colors used by the Default Design. */
export const BRAND_ORANGE = "#FF661E";
export const BRAND_ORANGE_DARK = "#A83A00";
export const BRAND_INK = "#111311";

/** Mantine expects a fixed 10-tuple (index 5 = base color). */
export type BrandScale = [string, string, string, string, string, string, string, string, string, string];

/** Builds a 10-step Mantine scale from one brand color (index 5 = base). */
export function brandScale(base: string): BrandScale {
  const rgb = [1, 3, 5].map((i) => parseInt(base.slice(i, i + 2), 16));
  const mix = (t: number[], f: number) =>
    "#" + rgb.map((c, i) => Math.round(c + (t[i] - c) * f).toString(16).padStart(2, "0")).join("");
  const white = [255, 255, 255];
  const black = [13, 18, 23];
  return [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map((i) =>
    i < 5 ? mix(white, 0.85 - i * 0.17) : i === 5 ? base : mix(black, (i - 5) * 0.15)
  ) as BrandScale;
}
