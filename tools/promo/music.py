"""Original soundtrack for the TermFold promo, synthesised from scratch (no samples, no licences).

120 BPM, A minor, i-VI-III-VII (Am F C G). One bar is two seconds, so every scene cut in
video.py lands on a bar line. Structure:
  bars 1-2   intro: filtered pad and a sparse pluck, a noise riser into...
  bar 3      the drop (logo reveal): kick, bass, clap, hats, arpeggio
  bars 3-11  main groove, sidechained to the kick
  bars 12-13 lift: fuller arp, second riser
  bar 14     final hit with a long tail

    py -3 tools/promo/music.py out.wav
"""
import sys
import wave

import numpy as np

SR = 44100
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 14
LENGTH = BARS * BAR + 1.5  # tail after the last hit

rng = np.random.default_rng(7)


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def t_axis(duration):
    return np.arange(int(duration * SR)) / SR


def adsr(n, a, d, s, r, sustain_len=None):
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    sustain_len = n - a - d - r if sustain_len is None else int(sustain_len * SR)
    sustain_len = max(sustain_len, 0)
    env = np.concatenate([
        np.linspace(0, 1, max(a, 1), endpoint=False),
        np.linspace(1, s, max(d, 1), endpoint=False),
        np.full(sustain_len, s),
        np.linspace(s, 0, max(r, 1)),
    ])
    return np.pad(env, (0, max(0, n - len(env))))[:n]


def saw(f, t):
    return 2 * ((f * t) % 1.0) - 1


def lowpass(x, cutoff):
    """One-pole low-pass; cutoff may be an array (a sweeping filter)."""
    cutoff = np.broadcast_to(cutoff, x.shape)
    alpha = 1 - np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += alpha[i] * (x[i] - acc)
        y[i] = acc
    return y


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def place(track, clip, at):
    start = int(at * SR)
    end = min(len(track), start + len(clip))
    if end > start:
        track[start:end] += clip[: end - start]


# A minor: Am, F, C, G as MIDI note sets (root, third, fifth, extension).
CHORDS = [
    [57, 60, 64, 67],  # Am7
    [53, 57, 60, 64],  # Fmaj7
    [48, 52, 55, 59],  # Cmaj7
    [55, 59, 62, 65],  # G7
]
ROOTS = [45, 41, 48, 43]  # A1 F1 C2 G1 (bass)


def pad(notes, duration):
    t = t_axis(duration)
    x = np.zeros_like(t)
    for n in notes:
        for cents in (-9, 0, 9):
            x += saw(midi(n + 12) * 2 ** (cents / 1200), t + rng.random())
    x /= len(notes) * 3
    return x * adsr(len(t), 0.35, 0.4, 0.8, 0.5)


def pluck(n, duration=0.28, bright=1.0):
    t = t_axis(duration)
    x = 0.6 * saw(midi(n), t) + 0.4 * np.sin(2 * np.pi * midi(n) * t)
    x = lowpass(x, 900 + 5200 * bright * np.exp(-t * 18))
    return x * np.exp(-t * 11)


def kick():
    t = t_axis(0.45)
    freq = 45 + 110 * np.exp(-t * 28)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    body = np.sin(phase) * np.exp(-t * 7.5)
    click = rng.standard_normal(len(t)) * np.exp(-t * 400) * 0.25
    return np.tanh(1.6 * (body + click))


def clap():
    t = t_axis(0.3)
    noise = rng.standard_normal(len(t))
    env = np.exp(-t * 22)
    # Three quick bursts, the way a real clap smears.
    for offset in (0.0, 0.011, 0.022):
        env += np.where(t > offset, np.exp(-(t - offset) * 90), 0) * 0.6
    x = highpass(lowpass(noise, 3800), 900) * env
    return x * 0.8


def hat(open_=False):
    t = t_axis(0.22 if open_ else 0.06)
    x = highpass(rng.standard_normal(len(t)), 7000)
    return x * np.exp(-t * (14 if open_ else 70)) * 0.35


def bass_note(n, duration):
    t = t_axis(duration)
    x = 0.7 * np.sin(2 * np.pi * midi(n) * t) + 0.3 * saw(midi(n), t)
    x = lowpass(x, 420)
    return x * adsr(len(t), 0.005, 0.08, 0.75, 0.05)


def riser(duration):
    t = t_axis(duration)
    x = rng.standard_normal(len(t))
    x = lowpass(x, 300 + 9000 * (t / duration) ** 2)
    return x * (t / duration) ** 2 * 0.5


def impact():
    t = t_axis(3.0)
    boom = np.sin(2 * np.pi * (40 + 60 * np.exp(-t * 6)) * t) * np.exp(-t * 1.6)
    wash = lowpass(rng.standard_normal(len(t)), 2500) * np.exp(-t * 2.2) * 0.4
    return boom + wash


def delay(x, time, feedback, mix):
    d = int(time * SR)
    y = x.copy()
    for k in range(1, 6):
        gain = feedback ** k
        if d * k >= len(x) or gain < 0.02:
            break
        y[d * k:] += x[: len(x) - d * k] * gain * mix
    return y


def main(out_path):
    n = int(LENGTH * SR)
    pads, bass, drums, arps, fx = (np.zeros(n) for _ in range(5))
    kick_env = np.zeros(n)  # for sidechain ducking

    for bar in range(BARS):
        start = bar * BAR
        chord = CHORDS[bar % 4]
        root = ROOTS[bar % 4]
        drop = bar >= 2
        last = bar == BARS - 1

        if last:
            place(fx, impact(), start)
            place(pads, pad(CHORDS[0], BAR + 1.2) * 0.9, start)
            place(drums, kick() * 1.1, start)
            continue

        place(pads, pad(chord, BAR), start)

        if not drop:
            # Intro: a sparse pluck on the chord tones, filter opening.
            for i, note in enumerate([chord[0] + 12, chord[2] + 12, chord[1] + 12, chord[3] + 12]):
                place(arps, pluck(note, bright=0.25 + 0.2 * bar) * 0.6, start + i * BEAT)
            continue

        for beat in range(4):
            at = start + beat * BEAT
            place(drums, kick() * 0.55, at)
            k = int(at * SR)
            kick_env[k:k + int(0.3 * SR)] = np.maximum(
                kick_env[k:k + int(0.3 * SR)], np.exp(-np.arange(int(0.3 * SR)) / SR * 14)[: n - k]
            )
            if beat in (1, 3):
                place(drums, clap(), at)
            place(drums, hat(open_=(beat == 3 and bar % 2 == 1)), at + BEAT / 2)
            if bar >= 11:  # the lift: sixteenth hats
                place(drums, hat() * 0.6, at + BEAT / 4)
                place(drums, hat() * 0.6, at + 3 * BEAT / 4)
            # Offbeat eighth-note bass, pumping against the kick.
            place(bass, bass_note(root, BEAT / 2 - 0.02), at + BEAT / 2)
            place(bass, bass_note(root, BEAT / 2 - 0.05) * 0.8, at)

        # Sixteenth-note arpeggio over two octaves of the chord.
        pattern = [0, 1, 2, 3, 2, 1, 2, 3]
        tones = chord + [c + 12 for c in chord]
        steps = 16 if bar >= 11 else 8
        for s in range(steps):
            idx = pattern[s % len(pattern)] + (4 if bar >= 11 and s >= 8 else 0)
            place(arps, pluck(tones[idx] + 12, bright=0.9) * 0.45, start + s * (BAR / steps))

    # Risers into the drop (bar 3) and into the final hit.
    place(fx, riser(2 * BEAT), 2 * BAR - 2 * BEAT)
    place(fx, riser(BAR), (BARS - 2) * BAR)

    duck = 1 - 0.7 * kick_env
    arps = delay(arps, 3 * BEAT / 4, 0.45, 0.6)
    # Balanced for phone speakers, which is where this will mostly be heard: the melodic parts
    # (pad, arp) carry the track, and the low end is kept in check because a phone cannot
    # reproduce it anyway and it would only eat headroom.
    mix = (
        0.50 * pads * duck
        + 0.22 * bass * duck
        + 0.60 * drums
        + 0.95 * arps * (0.6 + 0.4 * duck)
        + 0.45 * fx
    )
    mix = highpass(mix, 32)  # nothing useful lives below here
    # Gentle stereo: the arp and pad echo slightly later on the right.
    right = mix + 0.12 * np.roll(0.34 * arps + 0.26 * pads, int(0.012 * SR))
    left = mix
    stereo = np.stack([left, right], axis=1)
    stereo = np.tanh(stereo * 1.4)  # soft-clip glue
    stereo /= np.max(np.abs(stereo)) * 1.05
    # Fade the very end.
    fade = int(1.2 * SR)
    stereo[-fade:] *= np.linspace(1, 0, fade)[:, None]

    pcm = (stereo * 32767).astype(np.int16)
    with wave.open(out_path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"wrote {out_path} ({LENGTH:.1f}s)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "music.wav")
