# OpenCfMoto — User Guide

Put **Google Maps / Waze (via Android Auto)** on your CFMoto MotoPlay dashboard, wirelessly, without
rooting your phone. You can also mirror your whole phone screen to the dash.

> ⚠️ **This is a proof-of-concept / hobby project, not a polished product.** It was built and tested
> against a CFMoto **800MT** dash and a **Pixel 9**. It may glitch, need a retry, or not work on other
> bikes/phones. Don't rely on it for critical navigation, and set up your route **before** you start
> riding. Use at your own risk.

---

## 1. What you need

- **A CFMoto motorcycle with a MotoPlay / EasyConnect touchscreen dash.**
  Confirmed working: **800MT** (CFDL26 dash). Other models (e.g. the 675 SR-R / CFDL16) may work
  partially or not at all.
- **An Android phone**, Android **10 or newer**. (Tested on a Pixel 9.)
- **Google Android Auto** installed from the Play Store and set up once (see step 3). Android Auto is
  what actually runs Maps/Waze — OpenCfMoto just relays its screen to the bike.
- **The OpenCfMoto app** (`app-debug.apk`) — you install this manually (step 2).
- A **mobile-data plan** is recommended: the phone joins the bike's Wi-Fi for the dash link, so live
  maps/traffic come over cellular.

No root, no VPN, no PC required to ride.

---

## 2. Install the OpenCfMoto app

The app isn't on the Play Store — you sideload the APK.

1. Copy **`app-debug.apk`** (from this project) to your phone, or download it directly on the phone.
2. Tap it in a file manager to install.
3. Android will warn that it's from an "unknown source" — allow installation for your browser/file
   manager when prompted (Settings ▸ Apps ▸ *(that app)* ▸ *Install unknown apps*).
4. Open **Open CfMoto** once. When asked, **grant the permissions** it requests:
   - **Location** — required by Android to join the bike's Wi-Fi. (The app doesn't track you.)
   - **Camera** — to scan the bike's pairing QR code.
   - **Notifications** — so it can keep running in the background while you ride.
   - **Bluetooth** — optional, currently unused; you can deny it.

---

## 3. One-time Android Auto setup

Android Auto must be installed, set up, and allowed to start in "self / head-unit" mode.

1. Install **Android Auto** from the Play Store (on many phones it's pre-installed) and open it once to
   accept its terms / initial setup.
2. **Enable Android Auto Developer Mode:** open Android Auto's settings (in OpenCfMoto you can tap the
   **AA Settings** button to jump there), scroll to the bottom and tap **Version** about **10 times**
   until it says developer mode is enabled.
3. Open the **⋮ (Developer settings)** menu that now appears and make sure head-unit / unknown-car
   projection is allowed (the exact wording varies by Android Auto version — e.g. *"Add new cars to
   Android Auto"* / *"Unknown sources"* should be **on**).

You only do this once.

---

## 4. Ride setup — put Android Auto on the dash

Do this while parked. It takes about a minute the first time.

1. **On the bike dash:** open the **MotoPlay / phone-connection (EasyConnect) screen** so it shows its
   **pairing QR code**. (This is the same QR the official CFMoto app uses.)
2. **On the phone:** open **Open CfMoto** and tap **`Start AA`**.
3. The **QR scanner** opens — point it at the dash's QR code. The app reads your bike model and Wi-Fi
   from it and starts the Android Auto receiver.
4. Android Auto spins up in the background (you'll see log lines like `steady video reached`). After a
   few seconds the phone pops a **Wi-Fi dialog** asking to connect to your bike's hotspot
   (e.g. *"CFMOTO1565"*) — tap **Connect / Allow**.
5. The dash connects and **Android Auto appears on the dashboard**. 🎉

From now on you drive Android Auto **from the dash touchscreen** — tap and scroll on the bike screen to
control Maps/Waze. Your phone can be locked or in your pocket; the app keeps the link alive via a
persistent notification.

**To stop:** tap **`Stop`** in the app (or use the notification). This ends projection and disconnects
from the bike Wi-Fi so your phone returns to normal.

---

## 5. Alternative: mirror the whole phone screen

If you'd rather show your entire phone screen on the dash (any app, not just Android Auto):

1. Tap **`Start Mirror`**.
2. Approve the **"Start recording / casting"** screen-capture prompt.
3. Scan the bike QR when the scanner opens, and accept the Wi-Fi dialog as in step 4 above.

Mirroring shows everything on your phone, keeps the phone screen on, and uses more battery — Android
Auto mode (section 4) is the recommended way to navigate.

---

## 6. The buttons

| Button | What it does |
| --- | --- |
| **Start AA** | The main mode — scan the bike QR, then project **Android Auto** to the dash. |
| **Start Mirror** | Mirror your **whole phone screen** to the dash instead. |
| **Stop** | Stop everything and disconnect from the bike Wi-Fi. |
| **AA Settings** | Opens Google Android Auto's settings (handy for step 3). |
| **Share Logs** | Exports a diagnostic log you can send if you need help (see below). |
| **Clear Logs** | Clears the on-screen log. |

The dark panel filling most of the screen is a **live log** — normal to see lots of text; it's for
troubleshooting.

---

## 7. What's normal, and troubleshooting

**Normal behavior**
- A short black/blank moment on the dash while it connects — then the map appears.
- When you open **All Apps** on the dash or **take/receive a phone call**, the dash may **freeze for a
  couple of seconds**, then resume. This is expected.
- Navigation **voice prompts** come out of your phone / paired helmet headset, not the bike speakers.

**If something's wrong**

| Symptom | Try this |
| --- | --- |
| Dash stays **black** after connecting | Tap **Stop**, then **Start AA** again and re-scan the QR. Make sure the dash is on its phone-connection screen. |
| **No Wi-Fi dialog** appears / it won't connect | Confirm you accepted the location permission; move the phone next to the bike; tap **Stop** and retry. Some phones show the dialog behind Android Auto — swipe back to OpenCfMoto to find it. |
| **"Android Auto" never starts** | Re-check section 3 (developer mode + unknown sources). Open **AA Settings** and confirm Android Auto itself works. |
| Dash **froze and didn't recover** | Tap **Stop**, then **Start AA** again. |
| Wrong bike detected / odd resolution | Make sure you scanned **your** dash's QR (not an old screenshot). |

**Getting help:** reproduce the problem, then tap **Share Logs** and send the log file (e.g. to
yourself or the project). The log describes each step and makes issues diagnosable. See the project's
Reddit thread linked in the README.

---

## 8. Good to know / limitations

- **Set your destination before riding.** Enter navigation while parked.
- **Single-finger touch** (tap and swipe) is supported on the dash — no pinch-to-zoom yet; use the on
  screen **＋ / −** map buttons to zoom.
- Works over the bike's **Wi-Fi**; keep the phone reasonably close to the dash.
- It's a hobby PoC — expect occasional hiccups and the odd need to **Stop → Start** again.
- No root and no PC required.

---

*OpenCfMoto builds on the excellent [headunit-revived](https://github.com/andreknieriem/headunit-revived)
project. See the `docs/` folder for the technical/architecture write-ups.*
