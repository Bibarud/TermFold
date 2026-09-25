# TermFold launch film

A 50-second, 1080p60 film made with [Remotion](https://www.remotion.dev) from real screen
recordings of TermFold on a Lenovo tablet, with an original soundtrack synthesised in `music.py`.

- `src/Promo.tsx`: the whole edit (timeline, captions, camera moves over the tablet mockup)
- `music.py`: the soundtrack (`py -3 music.py public/music.wav`, needs numpy and scipy)
- `public/footage/*.mp4`: the device clips (not committed; recorded with `adb shell screenrecord`
  at 2944x1840, converted to 30 fps, 2208x1380, and cut into the segments named in `CLIPS`)

```bash
npm install
npm run studio    # preview and scrub
npm run render    # writes out/termfold-promo.mp4
```

Remotion is free for individuals and small teams; see its license for larger companies.
