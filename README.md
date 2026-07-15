# OpenCfMoto - 450SR — forked from [BojanJ](https://github.com/BojanJ/open-cfmoto/)

## download from [releases](https://github.com/ionutradu252/open-cfmoto/releases)

## fixes + new features
- fixes for wifi-direct, added profile for 2025 CFMOTO 450SR (model id 66660742) - CFDL16 display (landscape orientation) - tested on sdkVersion 0.9.23.4
- added logs for HID (buttons) input (for future buttons linking to android auto - doesn't seem to work on 450SR)
- phone emits a handshake so the headunit doesn't disconnect and reconnect after ~7 seconds

## current bugs
- occasional green screen flashing maybe due to encoder config (happens when a lot of stuff moves on screen)
- audio not working
- buttons not working (touchscreen should work)

---

*thanks a lot to [BojanJ](https://github.com/BojanJ/open-cfmoto/) for the work

*OpenCfMoto builds on the excellent [headunit-revived](https://github.com/andreknieriem/headunit-revived)
project. See the `docs/` folder for the technical/architecture write-ups.*
