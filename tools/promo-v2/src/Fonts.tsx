import React from "react";
import { continueRender, delayRender, staticFile } from "remotion";

const faces = [
  { family: "Outfit", file: "fonts/Outfit.ttf", weight: "100 900" },
  { family: "JetBrains Mono", file: "fonts/JetBrainsMono.ttf", weight: "100 800" },
];

// Load the brand fonts before any frame renders, so no frame is drawn in a fallback face.
let loaded: Promise<void> | null = null;
const load = () => {
  if (!loaded) {
    loaded = Promise.all(
      faces.map(async (f) => {
        const face = new FontFace(f.family, `url(${staticFile(f.file)})`, { weight: f.weight });
        await face.load();
        document.fonts.add(face);
      }),
    ).then(() => undefined);
  }
  return loaded;
};

export const Fonts: React.FC = () => {
  const [handle] = React.useState(() => delayRender("fonts"));
  React.useEffect(() => {
    load().then(() => continueRender(handle));
  }, [handle]);
  return null;
};
