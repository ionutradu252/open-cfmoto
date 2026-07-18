# OpenCFLink — android auto on cfmoto dashes

*forked from [BojanJ](https://github.com/BojanJ/open-cfmoto/) · was "OpenCfMoto"*

## download from [releases](https://github.com/ionutradu252/open-cfmoto/releases)
<img src="./docs/IMG_20260716_135927.jpg" width="300">

**2025 cfmoto 450SR** — model id `66660742`, cfdl16 display (landscape, 800x400), sdk `0.9.23.4`.
tested on a xiaomi 13 / android 16.

**cfmoto 800NK** — model id `37426`, cfdl26 `2.3.0.5`, 720x712 touchscreen. tested on a samsung
S948B / android 16.

other cfmoto dashes might work. the app remembers the screen your dash asks for and fits android auto
to it from the second connect on, so an unprofiled bike can come good on its own. if the first
connect looks wrong, reconnect once. if it still looks wrong, use report a problem in the logs tab.

## what works
- **android auto on the bike's screen** — maps, waze, spotify, whatever you already use
- **fills the whole dash**, smooth, nothing cropped, no flickering
- **set up once** — scan the qr the dash shows and that's the last time you'll see the scanner. after
  that: ignition on, open the app, it connects on its own
- **survives an ignition cycle** — stop for fuel, switch the bike off and on, it rejoins by itself
- **the handlebar buttons work** — scroll through menus, select, back, home. all remappable,
  including to "navigate to a saved address"
- **touch works on the 800NK**, including pinch to zoom
- **voice works** — ask the assistant for directions through your helmet mic without stopping
- **the dash goes dark after sunset** instead of blinding you with a white map
- **type a destination in the app** and the route shows up on the dash
- **sound works as usual** — music and nav voice reach your helmet the way they always did

## what doesn't
- **your music pauses while the handlebar buttons are set to drive android auto.** android only lets
  one app own those buttons at a time. switch it off in settings when you want music control back
- **450SR: holding ▲/▼** does nothing while android auto is on screen. on the 800NK those same keys
  are a plain left/right press and they do work
- **450SR: no double press of enter** — the dash can't send it fast enough to tell apart from two
  normal presses. use a volume double tap instead
- **800NK: fn, voice, ▲ and ▼ do nothing** — they never leave the bike, so there's nothing to hook.
  ▲/▼ are the dash's own volume, voice triggers its own speech engine
- only really tested on a 450SR and an 800NK. the 800MT shares the 800NK's model id and can't be told
  apart until it connects, so it gets the 800NK's geometry on the first connect

## in app screenshots
<img src="./docs/Screenshot_20260716-172032_Open%20CfMoto.png" width="300"> <img src="./docs/Screenshot_20260716-172039_Open%20CfMoto.png" width="300">

## fixes + new features

**connection**
- wifi-direct fixes + a profile for the 450SR. the dash hands out a `DIRECT-go-CFMOTO-…` ssid with no
  default route, so the bike is found at `.1` of the phone's own /24. it just gave up before
- the phone sends a heartbeat so the headunit doesn't drop the session every ~7s. this dash never
  sends its own, so the control socket sat idle and its watchdog killed it
- **the qr is remembered** — auto connects when you open the app, no scanning. rejoins by itself if
  you switch the bike off and back on, gives up after ~2 min so it doesn't drain the battery
- **the give-up actually fires now.** it never did: `requestNetwork` was called without a timeout, so
  a re-request for an ap that isn't there sat pending forever and none of
  onAvailable/onLost/onUnavailable ever came back. the retry chain only re-armed from onLost, so
  after one attempt it went quiet — wake lock held, encoder running, ~780 resyncs into a bike that
  wasn't there (7+ min seen in a log). it's a wall-clock deadline now, with a watchdog
- **the app remembers each bike's screen size** and fits AA to it exactly from the second connect

**picture**
- **green flashing fixed** — the encoder outran the dash's ~24fps pull and every dropped frame broke
  the h264 chain until the next keyframe. now paced to the dash, with a forced keyframe on overrun
- **fills the screen with nothing cropped** — AA can't render 800x400, so it's told to keep its ui
  inside the visible band (margins) and the leftover band is cropped away
- fill / letterbox toggle, applies live
- **picture quality setting, which is really an audio setting.** the dash link is wifi and your helmet
  is bluetooth; on 2.4ghz they share the band. a parked map costs ~120 kbps and a moving one ~600, so
  the faster you ride the busier the radio and the worse a2dp stutters. video never suffers (wifi
  wins the arbitration, bluetooth starves) which is why it's the music that breaks
- keyframes every 5s instead of every 1s. a ~20kb idr every second against ~500b p-frames was about a
  quarter of all airtime, in exactly the bursts that break audio — and redundant, since the media
  plane is tcp and the two moments that really need a keyframe already ask for one
- the log names the wifi band (`[wifi] link: …MHz`), so 2.4ghz contention is readable instead of a
  guess

**touch** (800NK and other touch dashes)
- **multi touch works, pinch to zoom.** the dash puts a pointer index at byte 6 of its touch frame,
  but the parser read `y` as a 32 bit int and ate that field into y's high half: every second-finger
  event arrived as `y = 65660` on a 704px panel and went to AA as a *first* finger. AA got two downs
  with no up between and a touch teleporting off screen, which is why taps landed wrong and why the
  decoder dropped to 2fps and stalled whenever anyone touched the screen. every pointer is tracked
  and reported now
- the frame layout (`action u16 | x u16 | y u16 | pointer u16 | timestamp u32 | 0x0003`) is decoded
  from real captured frames and pinned by unit tests

**control**
- **handlebar buttons drive android auto.** they never appear on the pxc link, they reach the phone as
  bluetooth avrcp. defaults: press ▲/▼ = knob, enter = select, ▼▼ = back, ▲▲ = home. toggle in
  settings, off = normal music/volume
- **every gesture is remappable** (settings → customize buttons) with a reset to defaults. every
  gesture a cfmoto dash is known to send × every action AA accepts — knob, d-pad, select, back, home,
  assistant, nothing — plus **navigate to a saved place**: put an address in a slot, map it to a
  button, and that button starts turn-by-turn with the phone in your pocket (needs "display over
  other apps", android blocks background apps from opening maps). want voice from the bars? map a
  double tap to assistant
- **800NK: ◀ and ▶ can be double tapped.** measured at ~230ms between taps with one event per press,
  so the timing is real — unlike the 450SR's enter, which is a hold and can't repeat fast enough. off
  by default: while a ×2 is mapped, scrolling fast with that button can read as a double
- a double tap is read from the *size* of the volume jump, not from two events close together: the
  dash coalesces both presses into one absolute-volume message, so the timing version never fired
- media focus is re-asserted once the bike link is up, so the dash re-reads the player it first saw
  ~8s too early (this is what toggling the switch off and on by hand was working around)
- on-screen d-pad + knob in the app
- **"navigate to…"** — type an address, turn-by-turn shows up on the dash
- AA is told we're a rotary head unit, or it renders no focus highlight to move

**voice**
- **the microphone works** — the phone is presented to AA as the head unit's mic, so the assistant can
  hear you. capture prefers the bluetooth headset (cardo → bike → phone) over the phone's own mic.
  there's a button in the app, and any gesture can be mapped to it

**night**
- **the dash goes dark after sunset.** it never did before: AA subscribes to the NIGHT sensor on every
  connect, we said "ok" and then never sent an event, so it sat in its day theme forever — a
  full-brightness white map at eye level on a night ride. worked out from sunrise/sunset at your last
  known location (no gps fix, so no battery cost), rechecked every minute so it flips mid ride, with
  an auto/day/night override in settings. the phone's light sensor is deliberately not used, it rides
  in a pocket and reads midnight at noon

**app**
- four tab material 3 ui — connect, control, settings, logs — with a live status header and a
  tutorial
- **report a problem** (logs tab) — say what the bike did and which bike it is, and the app fills in
  the screen profile, the panel the dash asked for, the wifi band, the app version and the phone, and
  attaches the log. opens your own email app, nothing is uploaded anywhere
- **logs are scrubbed as they're written.** they used to carry the bike's wifi password (the whole qr
  string was logged), the ota ftp credentials and the bike's serial. fine while share was a
  deliberate act, a liability with a one-tap report button. redaction happens on write so the buffer
  itself is safe to post. the ssid, model id and the first 4 chars of serials survive, since that's
  what makes a report diagnosable
- **the encoder throttles when the dash isn't looking.** switch to the gauges screen and the dash
  stops pulling frames but leaves the socket open, so nothing told us to stop and we kept encoding at
  full rate into a queue nobody drained. drops to ~2fps after 3s and snaps back with a keyframe
- log mirroring and the status refresh stop when the app isn't on screen, so a pocketed phone with
  the screen off isn't paying for ui it isn't showing
- logging for hid input, kept for future dashes. the 450SR sends nothing over pxc

## current bugs
- **AA audio isn't routed through the bike** — we throw its audio away. in practice sound still works,
  because AA plays through the phone → bluetooth → bike → helmet. the switch for doing it properly is
  in settings, disabled until it's built
- **the music player pauses while the bike-button toggle is on** — android gives the media buttons to
  one app, so taking them takes them from your player
- occasional brief video stutter under heavy input, no disconnect

## versions
- **v0.1.2.1** *(prerelease)* — **renamed to OpenCFLink** (package `dev.snaipdefix.opencflink`; the
  `-cfdl16` suffix is gone now that more than one display is supported). installs as a new app, so
  uninstall the old one and re-scan the qr once. adds the 800NK profile, multi touch, night mode,
  microphone + assistant, remappable buttons + navigate-to-a-saved-place, no-crop fill via margins,
  green flashing fixed, auto connect + a give-up that actually fires, picture quality setting, report
  a problem, four tab material 3 ui
- **v0.1.1-cfdl16** — wifi direct + 450SR profile, heartbeat fix, hid input logging

---
*thanks a lot to [BojanJ](https://github.com/BojanJ/open-cfmoto/) for the work*

---

*OpenCFLink builds on the excellent [headunit-revived](https://github.com/andreknieriem/headunit-revived)
project. See the `docs/` folder for the technical/architecture write-ups.*
