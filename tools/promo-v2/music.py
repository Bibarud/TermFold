"""The TermFold launch soundtrack, synthesised from scratch (no samples, nothing to license).

120 BPM in A minor, one chord per bar (2 s): Am9 - Fmaj9 - Cmaj9 - G6/9. The arrangement follows
the video's timeline (src/Promo.tsx):

   0-3 s    hook: FM electric piano chords in a big room, a riser into...
   3 s      logo impact
   5 s      the groove: kick, sub bass, clap, hats, pad and arpeggio, sidechained to the kick
  17 s      lift: 16th-note arpeggio and ghost hats
  36-41 s   breakdown under the bubble shot: drums out, pad opens, riser
  41 s      drop for "works with"
  45 s      end card impact and a long tail

Soft whooshes land on every caption and camera push. Everything is mixed through sends to a
convolution reverb and a ping-pong delay, glued, soft-clipped and limited.

    py -3 music.py public/music.wav
"""
import sys
import wave

import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

SR = 48000
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
LENGTH = 50.0
N = int(LENGTH * SR)
rng = np.random.default_rng(11)


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def at(t):
    return int(round(t * SR))


class Bus:
    """A stereo track."""

    def __init__(self):
        self.x = np.zeros((2, N))

    def add(self, t, sig, gain=1.0, pan=0.0):
        """Mixes a mono or stereo signal in at time t with an equal-power pan."""
        i = at(t)
        if i >= N:
            return
        if sig.ndim == 1:
            l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
            sig = np.vstack([sig * l, sig * r]) * np.sqrt(2)
        n = min(sig.shape[1], N - i)
        self.x[:, i:i + n] += sig[:, :n] * gain


def filt(x, kind, freq, order=2):
    sos = butter(order, freq, btype=kind, fs=SR, output="sos")
    return sosfilt(sos, x, axis=-1)


def env_exp(n, tau):
    return np.exp(-np.arange(n) / (tau * SR))


def adsr(n, a, r, sustain=1.0, d=0.0, s=1.0):
    a, r, d = int(a * SR), int(r * SR), int(d * SR)
    body = max(n - a - d - r, 0)
    e = np.concatenate([
        np.linspace(0, 1, max(a, 1)),
        np.linspace(1, s, max(d, 1)) if d else np.zeros(0),
        np.full(body, s),
        np.linspace(s, 0, max(r, 1)),
    ])
    return e[:n] * sustain


def polyblep_saw(freq, n, phase0=0.0):
    t = freq / SR
    ph = (phase0 + np.arange(n) * t) % 1.0
    y = 2 * ph - 1
    # PolyBLEP: smooth the discontinuity so high notes do not alias into grit.
    m = ph < t
    x = ph[m] / t
    y[m] -= x + x - x * x - 1
    m = ph > 1 - t
    x = (ph[m] - 1) / t
    y[m] -= x * x + x + x + 1
    return y


# ---- Harmony ---------------------------------------------------------------------------------

CHORDS = [  # (bass root, voicing)
    (45, [57, 60, 64, 67, 71]),  # Am9
    (41, [57, 60, 64, 67]),      # Fmaj9 (A C E G over F)
    (48, [55, 59, 62, 64]),      # Cmaj9 (G B D E over C)
    (43, [57, 59, 62, 64]),      # G6/9 (A B D E over G)
]


def chord_at(t):
    return CHORDS[int(t // BAR) % 4]


# ---- Instruments -----------------------------------------------------------------------------

def epiano(freq, dur, vel=1.0):
    """FM electric piano: a warm body plus a brief bell-like tine."""
    n = int(dur * SR)
    tt = np.arange(n) / SR
    idx = 1.8 * env_exp(n, 0.35) + 0.25
    tine = 0.9 * env_exp(n, 0.05)
    mod = np.sin(2 * np.pi * freq * tt) * idx + np.sin(2 * np.pi * freq * 14 * tt) * tine
    body = np.sin(2 * np.pi * freq * tt + mod)
    amp = env_exp(n, 1.4) * np.minimum(1, tt / 0.004)
    amp *= np.minimum(1, (dur - tt) / 0.25).clip(0, 1)
    trem = 1 + 0.06 * np.sin(2 * np.pi * 4.6 * tt)
    return body * amp * trem * vel


def pad(freqs, dur, cutoff_from, cutoff_to):
    """Seven-voice detuned saw pad through a slowly opening low-pass."""
    n = int(dur * SR)
    out = np.zeros((2, n))
    for f in freqs:
        for v in range(7):
            det = (v - 3) * 0.0036
            s = polyblep_saw(f * (1 + det), n, rng.random())
            pan = (v - 3) / 3 * 0.8
            out[0] += s * np.cos((pan + 1) * np.pi / 4)
            out[1] += s * np.sin((pan + 1) * np.pi / 4)
    out /= 7 * len(freqs) ** 0.5
    # Filter sweep, applied in short blocks.
    block = 2400
    y = np.zeros_like(out)
    for b in range(0, n, block):
        c = cutoff_from + (cutoff_to - cutoff_from) * (b / n)
        y[:, b:b + block] = filt(out[:, max(0, b - 4800):b + block], "low", c, 2)[:, -min(block, n - b):]
    return y * adsr(n, 0.9, 1.2)


def pluck(freq, dur=0.32, vel=1.0, bright=3200):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    tri = 2 * np.abs(2 * ((freq * tt) % 1) - 1) - 1
    s = 0.6 * np.sin(2 * np.pi * freq * tt) + 0.4 * tri
    s = filt(s, "low", bright, 2)
    return s * env_exp(n, 0.11) * np.minimum(1, tt / 0.002) * vel


def bass(freq, dur, vel=1.0):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    s = np.sin(2 * np.pi * freq * tt) + 0.25 * filt(polyblep_saw(freq, n), "low", 220, 2)
    s = np.tanh(s * 1.4)
    return s * adsr(n, 0.004, 0.06) * vel


def kick(vel=1.0):
    n = int(0.45 * SR)
    tt = np.arange(n) / SR
    f = 46 + 110 * np.exp(-tt / 0.035)
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-tt / 0.28)
    click = filt(rng.standard_normal(n), "high", 2500) * np.exp(-tt / 0.004) * 0.35
    return np.tanh((body + click) * 1.6) * vel


def clap(vel=1.0):
    n = int(0.4 * SR)
    tt = np.arange(n) / SR
    noise = filt(rng.standard_normal(n), "band", [900, 3200])
    e = np.zeros(n)
    for k, off in enumerate([0, 0.011, 0.022]):
        i = int(off * SR)
        e[i:] += np.exp(-(tt[: n - i]) / (0.007 if k < 2 else 0.16))
    return noise * e * 0.6 * vel


def hat(vel=1.0, open_=False):
    n = int((0.28 if open_ else 0.06) * SR)
    tt = np.arange(n) / SR
    s = filt(rng.standard_normal(n), "high", 7500, 4)
    return s * np.exp(-tt / (0.09 if open_ else 0.018)) * vel


def riser(dur, gain=1.0):
    n = int(dur * SR)
    p = np.linspace(0, 1, n)
    noise = rng.standard_normal(n)
    y = np.zeros(n)
    block = 2400
    for b in range(0, n, block):
        lo = 300 + 5500 * p[b] ** 2
        y[b:b + block] = filt(noise[max(0, b - 4800):b + block], "band", [lo, lo * 1.8], 2)[-min(block, n - b):]
    f = 180 * 2 ** (p * 2.6)
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.25
    return (y * 0.9 + tone) * p ** 2.2 * gain


def impact(gain=1.0, length=2.4):
    n = int(length * SR)
    tt = np.arange(n) / SR
    f = 30 + 45 * np.exp(-tt / 0.18)
    sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.7)
    hit = filt(rng.standard_normal(n), "low", 1800) * np.exp(-tt / 0.08) * 0.6
    shimmer = filt(rng.standard_normal(n), "high", 6000) * np.exp(-tt / 0.5) * 0.12
    return np.tanh((sub + hit + shimmer) * 1.3) * gain


def whoosh(dur=0.55, gain=1.0):
    n = int(dur * SR)
    p = np.linspace(0, 1, n)
    shape = np.sin(np.pi * p) ** 2
    noise = rng.standard_normal(n)
    y = np.zeros(n)
    block = 1200
    for b in range(0, n, block):
        c = 500 + 3500 * np.sin(np.pi * p[b])
        y[b:b + block] = filt(noise[max(0, b - 2400):b + block], "band", [c, c * 1.6], 2)[-min(block, n - b):]
    return y * shape * gain


# ---- Arrangement -----------------------------------------------------------------------------

keys, pads, arp, bassb, drums, fx = Bus(), Bus(), Bus(), Bus(), Bus(), Bus()
kicks = []

def grooving(t):
    return (5.0 <= t < 36.0) or (41.0 <= t < 45.0)


# Electric piano: held chords in the hook, rhythmic comps in the groove, sparse in the breakdown.
for bar in range(int(LENGTH // BAR) + 1):
    t0 = bar * BAR
    root, voicing = chord_at(t0)
    if t0 < 3:
        for j, n_ in enumerate(voicing):
            keys.add(t0 + j * 0.035, epiano(midi(n_), 3.0, 0.5), pan=(j - 2) * 0.25)
    elif t0 < 45:
        hits = [(0, 0.55, 1.6), (1.5, 0.35, 0.45), (2.5, 0.42, 1.2)] if grooving(t0) else [(0, 0.5, 2.0)]
        for off, vel, dur in hits:
            for j, n_ in enumerate(voicing):
                keys.add(t0 + off * BEAT + j * 0.012, epiano(midi(n_), dur, vel * 0.8), pan=(j - 2) * 0.22)
    else:
        for j, n_ in enumerate(CHORDS[0][1] + [76]):
            keys.add(45.0 + j * 0.05, epiano(midi(n_), 5.0, 0.55), pan=(j - 2) * 0.25)

# Pad: from the logo on, one chord per bar, opening up through the piece.
for bar in range(int(3 // BAR), int(LENGTH // BAR) + 1):
    t0 = max(bar * BAR, 3.0)
    if t0 >= 48:
        break
    _, voicing = chord_at(t0)
    cut_from = 900 if t0 < 17 else 1500
    cut_to = 2600 if t0 < 17 else (6500 if 36 <= t0 < 41 else 4200)
    dur = BAR + 0.9 if t0 < 45 else 5.0
    pads.add(t0, filt(pad([midi(n_) for n_ in voicing[:4]], dur, cut_from, cut_to), "high", 220, 2), gain=0.42)

# Arpeggio: 8ths from 5 s, 16ths from 17 s, through the breakdown too.
t = 5.0
while t < 45:
    _, voicing = chord_at(t)
    tones = voicing + [v + 12 for v in voicing]
    step = BEAT / 2 if t < 17 else BEAT / 4
    k = int(round((t - 5.0) / step))
    note = tones[(k * 3) % len(tones)] + 12
    vel = (0.55 if k % 2 == 0 else 0.35) * (1.0 if t >= 17 else 0.8)
    swing = (0.018 if (k % 2 == 1 and step < BEAT / 2) else 0)
    arp.add(t + swing, pluck(midi(note), vel=vel, bright=5200 + 2500 * (t > 36)), pan=np.sin(k * 0.9) * 0.45)
    t += step

# Bass: root on the beat, octave on the off-beat, following the chords.
t = 5.0
while t < 45:
    if grooving(t):
        root, _ = chord_at(t)
        b = (t - 5.0) / BEAT
        bassb.add(t, bass(midi(root), BEAT * 0.9, 0.9))
        bassb.add(t + BEAT / 2, bass(midi(root + 12), BEAT * 0.4, 0.45))
    t += BEAT

# Drums.
t = 5.0
while t < 45:
    b = round((t - 5.0) / BEAT)
    if grooving(t):
        drums.add(t, kick(0.95))
        kicks.append(t)
        if b % 2 == 1 and t >= 9:
            drums.add(t, clap(1.0), pan=0.05)
        drums.add(t + BEAT / 2 + 0.01, hat(0.6), pan=0.25)
        if b % 4 == 3:
            drums.add(t + BEAT / 2, hat(0.38, open_=True), pan=-0.2)
        if t >= 17:
            for q in (0.25, 0.75):
                drums.add(t + q * BEAT + 0.012 * (q == 0.75), hat(0.24 + 0.08 * rng.random()), pan=-0.3)
    t += BEAT

# Sound design.
fx.add(1.2, riser(1.8, 0.35), pan=0)
fx.add(3.0, impact(0.9))
fx.add(3.6, riser(1.4, 0.28))
fx.add(5.0, impact(0.45, 1.6))
fx.add(39.0, riser(2.0, 0.4))
fx.add(41.0, impact(0.7))
fx.add(43.6, riser(1.4, 0.3))
fx.add(45.0, impact(1.0, 3.5))
for i, tw in enumerate([5.45, 11.0, 17.05, 22.85, 25.05, 27.85, 30.05, 32.2, 36.05, 37.9]):
    fx.add(tw - 0.3, whoosh(0.6, 0.16), pan=(-0.4 if i % 2 else 0.4))

# ---- Mix -------------------------------------------------------------------------------------

# Sidechain: everything melodic ducks under each kick.
duck = np.ones(N)
for tk in kicks:
    i = at(tk)
    n = min(int(0.32 * SR), N - i)
    curve = 1 - 0.55 * np.exp(-np.arange(n) / (0.09 * SR))
    duck[i:i + n] = np.minimum(duck[i:i + n], curve)
duck = filt(duck, "low", 60, 1)
for bus in (pads, arp, bassb, keys):
    bus.x *= duck * (0.35 if bus is not keys else 0.8) + (0.65 if bus is not keys else 0.2)

# Room: a synthetic stereo impulse response (2.6 s, darkened), plus a ping-pong delay for the arp.
ir_n = int(2.6 * SR)
tt = np.arange(ir_n) / SR
ir = np.vstack([rng.standard_normal(ir_n), rng.standard_normal(ir_n)]) * np.exp(-tt / 0.62)
ir = filt(ir, "low", 5500, 2)
ir[:, : int(0.018 * SR)] = 0
ir /= np.sqrt((ir ** 2).sum(axis=1, keepdims=True))

def reverb(x):
    return np.vstack([fftconvolve(x[0], ir[0])[:N], fftconvolve(x[1], ir[1])[:N]])

def pingpong(x, delay, fb, n_taps=6):
    y = np.zeros_like(x)
    d = int(delay * SR)
    mono = x.mean(axis=0)
    for k in range(1, n_taps + 1):
        g = fb ** k
        ch = k % 2
        if d * k < N:
            y[ch, d * k:] += mono[: N - d * k] * g
    return filt(y, "low", 3500, 1)

arp.x += pingpong(arp.x, BEAT * 0.75, 0.42) * 0.55
send = keys.x * 0.55 + pads.x * 0.35 + arp.x * 0.4 + drums.x * 0.1 + fx.x * 0.45
wet = reverb(send)

keys.x = filt(keys.x, "high", 140, 2)
mix = (
    keys.x * 0.62
    + pads.x * 0.55
    + arp.x * 0.6
    + bassb.x * 0.34
    + drums.x * 0.72
    + fx.x * 0.8
    + wet * 0.34
)

# Tone: trim mud, a touch of air.
mix = filt(mix, "high", 28, 2)
# Tilt toward a modern, phone-friendly balance: less boom, more presence and air.
mix = mix - 0.35 * filt(mix, "low", 90, 2)
mix = mix + 0.55 * filt(mix, "high", 2500, 1) + 0.15 * filt(mix, "high", 8000, 1)

# Glue: a slow RMS compressor on the whole mix.
rms = np.sqrt(filt(mix.mean(axis=0) ** 2, "low", 8, 1).clip(1e-9))
thr = np.percentile(rms[at(6):at(35)], 70)
gain = np.where(rms > thr, (thr / rms) ** 0.35, 1.0)
mix *= filt(gain, "low", 4, 1)

# Fade the tail and master.
fade = np.ones(N)
fade[at(48.3):] = np.linspace(1, 0, N - at(48.3)) ** 1.5
mix *= fade
mix = mix / np.abs(mix).max() * 2.3
mix = np.tanh(mix) / np.tanh(2.3)
mix *= 10 ** (-1.0 / 20) / np.abs(mix).max()

out = sys.argv[1] if len(sys.argv) > 1 else "music.wav"
pcm = (mix.T * 32767).astype("<i2")
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print(f"wrote {out}: {LENGTH:.1f}s, rms {20 * np.log10(np.sqrt((mix ** 2).mean())):.1f} dBFS")
