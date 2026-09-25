import React from "react";
import {
  AbsoluteFill,
  Audio,
  Img,
  interpolate,
  OffthreadVideo,
  Sequence,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { C, EASE, FPS, GLIDE, MONO, SANS, s } from "./theme";
import { Fonts } from "./Fonts";

export const PROMO_SECONDS = 50;

// ---------------------------------------------------------------------------------------------
// Timeline (seconds). 120 BPM: one beat is 0.5 s, one bar 2 s; every cut lands on a beat.
// ---------------------------------------------------------------------------------------------
const T = {
  hook: [0, 3],
  logo: [3, 5],
  device: [5, 41.6],
  works: [41, 45],
  end: [45, 50],
} as const;

/** One piece of real footage inside the tablet screen. */
type Clip = {
  src: string;
  at: number; // seconds on the timeline
  until: number;
  from?: number; // seconds into the file
  rate?: number;
  appChrome?: boolean; // TermFold is on screen (so the keyboard pill needs covering)
};

const CLIPS: Clip[] = [
  { src: "term", at: 5, until: 11, from: 0.3, rate: 1.15, appChrome: true },
  { src: "claude", at: 11, until: 17, from: 2.5, rate: 1.0, appChrome: true },
  { src: "prompt", at: 17, until: 20.5, from: 2.0, rate: 1.4, appChrome: true },
  { src: "working", at: 20.5, until: 22.5, from: 0, rate: 2.5, appChrome: true },
  { src: "permission", at: 22.5, until: 25, from: 0, appChrome: true },
  { src: "reply", at: 25, until: 27.5, from: 0.5, appChrome: true },
  { src: "picker", at: 27.5, until: 30, from: 1.0, rate: 1.2, appChrome: true },
  { src: "files", at: 30, until: 31.8, from: 0.5, rate: 2, appChrome: true },
  { src: "editor", at: 31.8, until: 36, from: 3.0, rate: 2, appChrome: true },
  { src: "home", at: 36, until: 37.3, from: 0 },
  { src: "bubble", at: 37.3, until: 41.6, from: 4.0 },
];

type Caption = { at: number; until: number; kicker: string; title: string; accent?: string };

const CAPTIONS: Caption[] = [
  { at: 5.6, until: 10.7, kicker: "In any folder on your device", title: "A real Ubuntu terminal.", accent: "Ubuntu" },
  { at: 11.2, until: 16.7, kicker: "Not a remote session", title: "Claude Code, on the tablet itself.", accent: "on the tablet" },
  { at: 17.2, until: 24.7, kicker: "Agent Client Protocol", title: "Or chat with it natively.", accent: "natively" },
  { at: 25.2, until: 29.7, kicker: "Tables · tools · models", title: "Everything renders like an app.", accent: "an app" },
  { at: 30.2, until: 35.7, kicker: "Files and editor built in", title: "Every file, one tap away.", accent: "one tap" },
  { at: 36.2, until: 41.2, kicker: "Android bubbles", title: "Leave the app. It keeps working.", accent: "keeps working" },
];

// ---------------------------------------------------------------------------------------------
// Camera: the tablet is one continuous object; shots are camera moves over it.
// ---------------------------------------------------------------------------------------------
type Cam = { y: number; s: number; rx: number; ry: number; fx: number; fy: number; o: number };
const base: Cam = { y: 0, s: 1, rx: 0, ry: 0, fx: 0.5, fy: 0.5, o: 1 };
const K = (t: number, c: Partial<Cam>): [number, Cam] => [t, { ...base, ...c }];

const CAMERA: [number, Cam][] = [
  K(5.0, { y: 760, s: 0.86, rx: 34, o: 0 }),
  K(6.3, { y: 40, s: 0.93, rx: 10, o: 1 }),
  K(7.0, { y: 20, s: 0.95, rx: 6 }),
  // The terminal: push in on the output as it prints
  K(8.0, { s: 1.7, fx: 0.3, fy: 0.24 }),
  K(10.8, { s: 1.78, fx: 0.31, fy: 0.26 }),
  // Claude Code: the prompt, then its answer
  K(11.7, { s: 1.65, fx: 0.42, fy: 0.6 }),
  K(16.7, { s: 1.72, fx: 0.42, fy: 0.62 }),
  // Native chat: the prompt being typed, the sent message and tool call, then the permission prompt
  K(17.7, { s: 1.45, fx: 0.5, fy: 0.82 }),
  K(20.3, { s: 1.5, fx: 0.5, fy: 0.82 }),
  K(21.0, { s: 1.35, fx: 0.55, fy: 0.24 }),
  K(22.4, { s: 1.38, fx: 0.55, fy: 0.25 }),
  K(23.1, { s: 1.42, fx: 0.5, fy: 0.46 }),
  K(24.9, { s: 1.46, fx: 0.5, fy: 0.46 }),
  // The reply's table, then the model picker
  K(25.6, { s: 1.34, fx: 0.47, fy: 0.62 }),
  K(27.4, { s: 1.37, fx: 0.47, fy: 0.62 }),
  K(28.1, { s: 1.5, fx: 0.74, fy: 0.66 }),
  K(29.9, { s: 1.54, fx: 0.74, fy: 0.66 }),
  // Files, then the editor
  K(30.6, { s: 1.12, fx: 0.36, fy: 0.45 }),
  K(31.8, { s: 1.14, fx: 0.36, fy: 0.45 }),
  K(32.5, { s: 1.28, fx: 0.55, fy: 0.48 }),
  K(35.9, { s: 1.34, fx: 0.55, fy: 0.56 }),
  // Bubble over the home screen
  K(36.6, { s: 0.96, rx: 5, ry: 6 }),
  K(37.3, { s: 0.98, rx: 4, ry: 5 }),
  K(38.2, { s: 1.24, fx: 0.72, fy: 0.53, rx: 2, ry: 3 }),
  K(41.0, { s: 1.3, fx: 0.72, fy: 0.54, rx: 1, ry: 2 }),
  K(41.6, { s: 1.12, y: 60, o: 0 }),
];

const camAt = (t: number): Cam => {
  if (t <= CAMERA[0][0]) return CAMERA[0][1];
  for (let i = 0; i < CAMERA.length - 1; i++) {
    const [t0, a] = CAMERA[i];
    const [t1, b] = CAMERA[i + 1];
    if (t <= t1) {
      const p = GLIDE(Math.min(1, Math.max(0, (t - t0) / (t1 - t0))));
      const out = {} as Cam;
      (Object.keys(a) as (keyof Cam)[]).forEach((k) => (out[k] = a[k] + (b[k] - a[k]) * p));
      return out;
    }
  }
  return CAMERA[CAMERA.length - 1][1];
};

// Screen geometry. Footage is 2208x1380; the status bar (top 63 px) and the right edge's
// system handles (48 px) are cropped away, leaving a 2160x1317 picture.
const SCREEN_W = 1440;
const K_PX = SCREEN_W / 2160;
const SCREEN_H = Math.round(1317 * K_PX);
const BEZEL = 16;

// ---------------------------------------------------------------------------------------------

export const Promo: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / FPS;
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg, fontFamily: SANS, overflow: "hidden" }}>
      <Fonts />
      <Backdrop t={t} />

      <Sequence from={s(T.hook[0])} durationInFrames={s(T.hook[1] - T.hook[0])}>
        <Hook />
      </Sequence>
      <Sequence from={s(T.logo[0])} durationInFrames={s(T.logo[1] - T.logo[0] + 0.2)}>
        <LogoReveal />
      </Sequence>

      <Device t={t} />
      {CAPTIONS.map((c) => (
        <Sequence key={c.title} from={s(c.at)} durationInFrames={s(c.until - c.at)}>
          <CaptionView caption={c} />
        </Sequence>
      ))}

      <Sequence from={s(T.works[0])} durationInFrames={s(T.works[1] - T.works[0])}>
        <WorksWith />
      </Sequence>
      <Sequence from={s(T.end[0])} durationInFrames={s(T.end[1] - T.end[0])}>
        <EndCard />
      </Sequence>

      <Grain frame={frame} />
      <Vignette />
      <Audio src={staticFile("music.wav")} />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------------------------------------
// Background, grain, vignette
// ---------------------------------------------------------------------------------------------

const Backdrop: React.FC<{ t: number }> = ({ t }) => {
  const drift = Math.sin(t * 0.35) * 60;
  const warm = interpolate(t, [0, 3, 5, 41, 45, 50], [0.35, 0.5, 0.85, 0.85, 0.55, 0.9]);
  return (
    <AbsoluteFill>
      <div
        style={{
          position: "absolute",
          width: 1800,
          height: 1800,
          left: 780 + drift,
          top: 380 - drift * 0.4,
          borderRadius: "50%",
          background: "radial-gradient(circle, rgba(255,110,30,0.30) 0%, rgba(255,90,20,0.10) 35%, transparent 65%)",
          opacity: warm,
          filter: "blur(40px)",
        }}
      />
      <div
        style={{
          position: "absolute",
          width: 1400,
          height: 1400,
          left: -700 - drift * 0.5,
          top: -800,
          borderRadius: "50%",
          background: "radial-gradient(circle, rgba(120,130,255,0.10) 0%, transparent 60%)",
        }}
      />
    </AbsoluteFill>
  );
};

const NOISE =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    `<svg xmlns='http://www.w3.org/2000/svg' width='240' height='240'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.55 0'/></filter><rect width='100%' height='100%' filter='url(#n)'/></svg>`,
  );

const Grain: React.FC<{ frame: number }> = ({ frame }) => {
  const step = Math.floor(frame / 2);
  const x = (step * 73) % 240;
  const y = (step * 131) % 240;
  return (
    <AbsoluteFill
      style={{
        backgroundImage: `url("${NOISE}")`,
        backgroundPosition: `${x}px ${y}px`,
        opacity: 0.05,
        mixBlendMode: "overlay",
        pointerEvents: "none",
      }}
    />
  );
};

const Vignette: React.FC = () => (
  <AbsoluteFill
    style={{ background: "radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,0.55) 100%)", pointerEvents: "none" }}
  />
);

// ---------------------------------------------------------------------------------------------
// Type
// ---------------------------------------------------------------------------------------------

/** Words rise into place one after another, softening out of a blur. */
const Words: React.FC<{
  text: string;
  accent?: string;
  size: number;
  weight?: number;
  delay?: number;
  stagger?: number;
  color?: string;
  exitAt?: number;
}> = ({ text, accent, size, weight = 600, delay = 0, stagger = 4, color = C.text, exitAt }) => {
  const frame = useCurrentFrame();
  const accentWords = new Set((accent ?? "").split(" ").filter(Boolean));
  const words = text.split(" ");
  const exit = exitAt === undefined ? 0 : interpolate(frame, [exitAt, exitAt + 14], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  return (
    <span style={{ display: "inline-flex", flexWrap: "wrap", justifyContent: "center", columnGap: size * 0.26 }}>
      {words.map((w, i) => {
        const p = interpolate(frame, [delay + i * stagger, delay + i * stagger + 26], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
          easing: EASE,
        });
        const clean = w.replace(/[.,]/g, "");
        const isAccent = accentWords.has(clean);
        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              fontSize: size,
              fontWeight: weight,
              letterSpacing: -size * 0.035,
              lineHeight: 1.08,
              color: isAccent ? C.accent : color,
              opacity: p * (1 - exit),
              transform: `translateY(${(1 - p) * size * 0.45 - exit * size * 0.25}px)`,
              filter: `blur(${(1 - p) * 10 + exit * 8}px)`,
            }}
          >
            {w}
          </span>
        );
      })}
    </span>
  );
};

const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const out = interpolate(frame, [s(2.55), s(3)], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", opacity: out }}>
      <div style={{ textAlign: "center", width: 1500 }}>
        <Words text="Your Android tablet" size={112} delay={4} stagger={6} />
        <div style={{ height: 8 }} />
        <Words text="is a dev machine now." accent="dev machine" size={112} delay={s(0.9)} stagger={6} />
      </div>
    </AbsoluteFill>
  );
};

const LogoReveal: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 14, stiffness: 120, mass: 0.8 } });
  const wipe = interpolate(frame, [8, 38], [0, 100], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  const out = interpolate(frame, [s(1.75), s(2.15)], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const lift = interpolate(frame, [s(1.75), s(2.15)], [0, -40], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  const glow = interpolate(frame, [0, 10, 60], [0, 1, 0.35], { extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", opacity: out, transform: `translateY(${lift}px)` }}>
      <div
        style={{
          position: "absolute",
          width: 700,
          height: 700,
          borderRadius: "50%",
          background: "radial-gradient(circle, rgba(255,122,46,0.35), transparent 60%)",
          opacity: glow,
          filter: "blur(30px)",
        }}
      />
      <div style={{ display: "flex", alignItems: "center", gap: 36 }}>
        <Img src={staticFile("logo.png")} style={{ width: 150, transform: `scale(${0.55 + pop * 0.45}) rotate(${(1 - pop) * -12}deg)`, opacity: pop }} />
        <div style={{ clipPath: `inset(0 ${100 - wipe}% 0 0)` }}>
          <div style={{ fontSize: 150, fontWeight: 700, letterSpacing: -6, color: C.text, lineHeight: 1 }}>TermFold</div>
        </div>
      </div>
      <div style={{ position: "absolute", top: 640 }}>
        <Words text="Real Ubuntu. Real agents. No root." size={36} weight={400} color={C.dim} delay={22} stagger={3} />
      </div>
    </AbsoluteFill>
  );
};

const CaptionView: React.FC<{ caption: Caption }> = ({ caption }) => {
  const frame = useCurrentFrame();
  const len = s(caption.until - caption.at);
  const kickerIn = interpolate(frame, [0, 20], [0, 1], { extrapolateRight: "clamp", easing: EASE });
  const out = interpolate(frame, [len - 16, len], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ alignItems: "center", pointerEvents: "none" }}>
      {/* A soft band keeps the text legible when the camera pushes in underneath it. */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          height: 260,
          background: "linear-gradient(to bottom, rgba(6,6,7,0.92), rgba(6,6,7,0.6) 60%, transparent)",
          opacity: out,
        }}
      />
      <div style={{ position: "absolute", top: 44, textAlign: "center", opacity: out }}>
        <div
          style={{
            fontSize: 21,
            fontWeight: 500,
            letterSpacing: 5,
            textTransform: "uppercase",
            color: C.accent,
            opacity: kickerIn,
            transform: `translateY(${(1 - kickerIn) * 10}px)`,
            marginBottom: 14,
          }}
        >
          {caption.kicker}
        </div>
        <Words text={caption.title} accent={caption.accent} size={62} delay={5} stagger={3} />
      </div>
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------------------------------------
// The tablet
// ---------------------------------------------------------------------------------------------

const Device: React.FC<{ t: number }> = ({ t }) => {
  if (t < T.device[0] || t > T.device[1]) return null;
  const cam = camAt(t);
  const m = Math.min(1, Math.max(0, (cam.s - 1) / 0.35));
  const dx = -(cam.fx - 0.5) * SCREEN_W * cam.s * m;
  const dy = -(cam.fy - 0.5) * SCREEN_H * cam.s * m;
  return (
    <AbsoluteFill style={{ perspective: 2200, alignItems: "center", justifyContent: "center" }}>
      <div
        style={{
          transform: `translate(${dx}px, ${dy + 70 + cam.y}px) rotateX(${cam.rx}deg) rotateY(${cam.ry}deg) scale(${cam.s})`,
          opacity: cam.o,
          transformStyle: "preserve-3d",
        }}
      >
        <div
          style={{
            padding: BEZEL,
            borderRadius: 46,
            background: "linear-gradient(160deg, #2c2c31 0%, #16161a 45%, #0e0e11 100%)",
            boxShadow:
              "0 80px 160px rgba(0,0,0,0.65), 0 30px 60px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.10), inset 0 0 0 1px rgba(255,255,255,0.05)",
            position: "relative",
          }}
        >
          {/* Front camera */}
          <div style={{ position: "absolute", top: 6, left: "50%", width: 5, height: 5, marginLeft: -2.5, borderRadius: 3, background: "#26262b" }} />
          <div style={{ width: SCREEN_W, height: SCREEN_H, borderRadius: 30, overflow: "hidden", position: "relative", background: "#08080a" }}>
            {CLIPS.map((c, i) => (
              <ClipView key={c.src} clip={c} first={i === 0} t={t} />
            ))}
            {/* A faint glass sheen */}
            <div
              style={{
                position: "absolute",
                inset: 0,
                background: "linear-gradient(115deg, rgba(255,255,255,0.06) 0%, rgba(255,255,255,0.0) 28%, rgba(255,255,255,0) 70%, rgba(255,255,255,0.03) 100%)",
                pointerEvents: "none",
              }}
            />
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

const FADE = 9; // frames of cross-dissolve between clips

const ClipView: React.FC<{ clip: Clip; first: boolean; t: number }> = ({ clip, first, t }) => {
  const start = s(clip.at);
  const len = s(clip.until - clip.at) + FADE;
  const local = Math.round(t * FPS) - start;
  if (local < 0 || local >= len) return null;
  const opacity = first ? 1 : interpolate(local, [0, FADE], [0, 1], { extrapolateRight: "clamp" });
  return (
    <Sequence from={start} durationInFrames={len} layout="none">
      <div style={{ position: "absolute", inset: 0, opacity }}>
        <OffthreadVideo
          src={staticFile(`footage/${clip.src}.mp4`)}
          startFrom={Math.round((clip.from ?? 0) * FPS)}
          playbackRate={clip.rate ?? 1}
          muted
          style={{ position: "absolute", left: 0, top: -63 * K_PX, width: 2208 * K_PX, height: 1380 * K_PX }}
        />
        {clip.appChrome && (
          // Lenovo's floating keyboard toolbar sits over the app's empty side rail.
          <div
            style={{
              position: "absolute",
              left: 3 * K_PX,
              width: 126 * K_PX,
              top: (860 - 63) * K_PX,
              height: 300 * K_PX,
              background: "linear-gradient(to bottom, rgb(11,9,10), rgb(17,11,10))",
            }}
          />
        )}
      </div>
    </Sequence>
  );
};

// ---------------------------------------------------------------------------------------------
// Closing
// ---------------------------------------------------------------------------------------------

const AGENTS = ["Claude Code", "Codex", "OpenCode", "Pi", "Cursor", "Gemini CLI", "Devin", "Antigravity"];

const WorksWith: React.FC = () => {
  const frame = useCurrentFrame();
  const len = s(T.works[1] - T.works[0]);
  const out = interpolate(frame, [len - 14, len], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const kicker = interpolate(frame, [s(0.5), s(0.9)], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", opacity: out }}>
      <div style={{ fontSize: 22, letterSpacing: 6, textTransform: "uppercase", color: C.accent, fontWeight: 500, opacity: kicker, marginBottom: 34 }}>
        Works with the agents you already use
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", width: 1400, rowGap: 22, columnGap: 54 }}>
        {AGENTS.map((a, i) => {
          const p = interpolate(frame, [s(0.7) + i * 4, s(0.7) + i * 4 + 24], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
          return (
            <span
              key={a}
              style={{
                fontSize: 64,
                fontWeight: 500,
                letterSpacing: -2,
                color: C.text,
                opacity: p * (i < 2 ? 1 : 0.78),
                transform: `translateY(${(1 - p) * 26}px)`,
                filter: `blur(${(1 - p) * 8}px)`,
              }}
            >
              {a}
            </span>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

const EndCard: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 16, stiffness: 110 } });
  const line = interpolate(frame, [s(0.9), s(1.6)], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  const url = interpolate(frame, [s(0.7), s(1.2)], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: EASE });
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 30, opacity: pop, transform: `scale(${0.9 + pop * 0.1})` }}>
        <Img src={staticFile("logo.png")} style={{ width: 118 }} />
        <div style={{ fontSize: 128, fontWeight: 700, letterSpacing: -5, color: C.text, lineHeight: 1 }}>TermFold</div>
      </div>
      <div style={{ marginTop: 28 }}>
        <Words text="Free and open source · No root · Android 8+" size={34} weight={400} color={C.dim} delay={s(0.35)} stagger={2} />
      </div>
      <div
        style={{
          marginTop: 46,
          padding: "18px 34px",
          borderRadius: 18,
          border: `1.5px solid rgba(255,122,46,${0.25 + line * 0.5})`,
          background: "rgba(255,122,46,0.06)",
          fontFamily: MONO,
          fontSize: 38,
          color: C.text,
          opacity: url,
          transform: `translateY(${(1 - url) * 18}px)`,
          position: "relative",
          overflow: "hidden",
        }}
      >
        github.com/Bibarud/<span style={{ color: C.accent }}>TermFold</span>
        <div style={{ position: "absolute", left: 0, bottom: 0, height: 3, width: `${line * 100}%`, background: C.accent }} />
      </div>
    </AbsoluteFill>
  );
};
