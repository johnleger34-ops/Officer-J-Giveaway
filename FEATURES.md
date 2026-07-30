# Implemented behavior

## Wheel spinner
- Every purchased chance becomes its own numbered slot.
- The app randomly chooses the winning numbered entry before starting the animation.
- The wheel spins for five seconds and stops on the chosen entry.
- The Officer J logo remains centered on the wheel.

## Raffle
- Every purchased ticket becomes its own numbered entry.
- The animation displays a branded white winner slip rising from a raffle box.
- The selected person's name and exact raffle number appear on the slip.

## Entries
- `John Smith` adds one chance.
- `John Smith,5` adds five chances.
- `John Smith x5` adds five chances.
- Five chances beginning with number 10 are numbered 10 through 14.

## Winner workflow
- Spin or draw again without saving
- Save result to winner history
- Remove the exact winning chance
- Generate a five-second MP4 result video for the phone gallery

## Wheel display update
- Every unique entrant name is rendered on the wheel.
- Duplicate entries are merged into one proportionally larger slice.
- Each numbered ticket still has an equal chance of winning.
- Names scale automatically for large entrant lists.
