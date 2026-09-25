import { Easing } from "remotion";

export const FPS = 60;

export const C = {
  bg: "#060607",
  text: "#F4F4F6",
  dim: "#8B8B93",
  faint: "#55555C",
  accent: "#FF7A2E",
  accentSoft: "rgba(255,122,46,0.14)",
};

export const SANS = "Outfit, sans-serif";
export const MONO = "JetBrains Mono, monospace";

/** The one easing curve everything moves on: quick start, long soft landing. */
export const EASE = Easing.bezier(0.16, 1, 0.3, 1);
/** For camera moves between shots: symmetric and unhurried. */
export const GLIDE = Easing.bezier(0.65, 0, 0.35, 1);

export const s = (seconds: number) => Math.round(seconds * FPS);
