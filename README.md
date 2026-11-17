# Do Re Mi Ball

Sing to play. Boards with a hole roll in from the right, each flagged with a note
(Do, Re, Mi...). Your voice sets the ball's height: sing that note and the ball
lifts to the hole and slips through. Go quiet and it drops to the floor.
Your selfie camera is the backdrop.

## Levels

| # | Name | What's new |
|---|------|------------|
| 1 | Do Re Mi | Three notes, slow boards |
| 2 | Up to Sol | Fa and Sol, bonus stars |
| 3 | The Whole Ladder | All eight notes |
| 4 | Sliders | Holes drift up and down |
| 5 | Twinkle | The boards spell out a tune |
| 6 | Tunnels | Long boards, hold the note |
| 7 | Trapdoors | Holes open at the last second |
| 8 | Leaps | Boards in pairs, big jumps |
| 9 | Squeeze | Holes shrink as they approach |
| 10 | Ode to Joy | Faster tune, disco lights |
| 11+ | Chaos | Two random twists at once, faster every level |

Boards are solid: off the note, the ball hits the wood and gets shoved left. Get on
the note and it pops through; get pushed off the screen and you lose a life.
Three lives, +1 for each level cleared (max 5). Streaks multiply your points and
staying centred in the hole scores a "Perfect".

## Get the APK

Every push to `main` builds the app with GitHub Actions and publishes it at
**Releases > latest > DoReMiBall.apk**. Open that page on your phone, download,
and install (allow "install unknown apps" for your browser when asked).

Local build: open the folder in Android Studio, or run `./gradlew assembleDebug`.

## First run

1. Allow the microphone (required) and camera (optional backdrop).
2. **Tune**: hold a comfortable low note for a second. That becomes your Do, and
   the app plays your scale back. Re-tune any time from the menu.
3. Headphones help: without them the mic can hear the game's own guide tones.

## Code map

- `PitchDetector` - mic capture and YIN pitch tracking (about 86 readings a second)
- `GameView` - levels, physics, drawing, menus
- `CameraBackground` - front camera preview (Camera2 on a TextureView)
- `Synth` - guide tones and sound effects generated in code
- `MainActivity` - permissions and lifecycle

No third-party libraries. Min Android 8.0.

`keystore/doremi.jks` is a throwaway signing key kept in the repo on purpose so
each build installs over the previous one. Swap it before publishing anywhere public.
