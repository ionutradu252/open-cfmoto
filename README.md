# OpenCFLink — android auto on cfmoto dashes

*forked from [BojanJ](https://github.com/BojanJ/open-cfmoto/) · was "OpenCfMoto"*

## download from [releases](https://github.com/ionutradu252/open-cflink/releases)
<img src="./docs/IMG_20260716_135927.jpg" width="300">

## tested bikes
| fully working features |
|---|
| **CFMOTO 450SR** |
| **CFMOTO 450MT** |
| **CFMOTO 800NK** |
| **CFMOTO 800MT** |
| **CFMOTO 1000MT-X** |

huge thanks for testing stuff out:
[u/Hu_2y](https://www.reddit.com/user/Hu_2y/), [Plovri](https://github.com/Plovri)

other cfmoto dashes might work. the app measures your screen on the first connect and fits android
auto to it from then on, so an unlisted bike can come good on its own. if the first connect looks
wrong, reconnect once. if it still looks wrong, use **report a problem** in the logs tab.

## what works

- **android auto on the bike's screen** — maps, waze, spotify, whatever you already use
- **fills the whole dash** — smooth, nothing cropped, no flickering
- **set up once** — scan the qr the dash shows and you'll never see the scanner again. after that:
  ignition on, open the app, it connects on its own
- **survives an ignition cycle** — stop for fuel, switch the bike off and on, it rejoins by itself
- **the handlebar buttons work** — scroll menus, select, back, home. all remappable, including to
  "navigate to a saved address"
- **touch works on the 800NK**, pinch to zoom included
- **use your phone as the screen** — open the dash view in the app and tap it there. handy for
  setting a destination while stopped
- **voice works** — ask the assistant for directions through your helmet mic without stopping
- **goes dark after sunset** instead of blinding you with a white map
- **type a destination in the app** and the route shows up on the dash
- **sound works as usual** — music and nav voice reach your helmet the way they always did
- **tells you when there's an update**

## what doesn't

- **your music pauses while the handlebar buttons are set to drive android auto.** android only lets
  one app own those buttons at a time. switch it off in settings when you want music control back
- **450SR: no double press of enter** — the dash can't send it fast enough to tell apart from two
  normal presses. use a volume double tap instead
- **800NK: fn, voice, ▲ and ▼ do nothing** — those buttons never leave the bike. ▲/▼ are the dash's
  own volume and voice runs its own speech engine

## easy on the battery

measured over a real ride ~ 3hrs, balanced quality, 5ghz:

| app | battery |
|---|---|
| android auto | 26% |
| waze | 13% |
| music player | 11% |
| **opencflink** | **4%** |

the app used about 7% of what the whole setup uses — less than the music player, even though it's the thing
running the screen and the wifi link.

## getting started

1. install android auto on your phone and set it up once
2. in android auto's settings, turn on **"add new cars to android auto"**
3. ignition on, open **motoplay** on the dash so the qr appears
4. open opencflink, tap **connect**, scan the qr
5. that's it. next time just open the app

## in app screenshots
<img src="./docs/Screenshot_20260716-172032_Open%20CfMoto.png" width="300"> <img src="./docs/Screenshot_20260716-172039_Open%20CfMoto.png" width="300">

## settings worth knowing

- **picture quality** — if your music stutters while riding, turn this down. on 2.4ghz wifi the
  screen and your bluetooth audio share the same airwaves and the music loses. 5ghz is much better
  if your bike offers it
- **screen margins** — keeps android auto away from an edge. on an 800NK a top margin clears
  motoplay's pull-down arrow so it stops stealing your swipes
- **customize buttons** — remap every handlebar gesture. the list shows the buttons on *your* bike
- **day / night** — automatic from sunset, or force either
- **fill / letterbox** — fill uses the whole screen, letterbox shows everything with black bars

## versions

- **v0.1.3** — first stable. 800NK touch working properly (taps, drags and pinch), dash view on the
  phone, screen margins, update check, 450SR ▲/▼ hold mappable again, lots of touch and connection
  fixes
- **v0.1.2.1** *(prerelease)* — renamed to OpenCFLink. installs as a new app, so uninstall the old
  one and scan the qr once. added the 800NK, night mode, microphone, remappable buttons,
  navigate-to-a-saved-place, no-crop fill, report a problem, new four tab ui
- **v0.1.1-cfdl16** — wifi direct + 450SR support, connection fix

## something not working?

use **report a problem** in the logs tab. it fills in your bike and setup and attaches the log —
passwords and serial numbers are stripped out automatically. it opens your own email app; nothing is
uploaded anywhere.

---
*thanks a lot to [BojanJ](https://github.com/BojanJ/open-cfmoto/) for the work*

*OpenCFLink builds on the excellent [headunit-revived](https://github.com/andreknieriem/headunit-revived)
project. See the `docs/` folder for the technical write-ups.*
