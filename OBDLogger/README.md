# OBD Logger (Android, ELM327 Bluetooth)

Native Kotlin + Jetpack Compose + Room (SQLite).

## Get the APK without Android Studio (GitHub, free)
1. Make a free account at github.com and create a new repository (private is fine).
2. Upload everything from this folder, including the hidden `.github` folder. On a computer, drag the folder contents onto the repo page.
   If `.github` doesn't upload, use Add file > Create new file, name it `.github/workflows/build-apk.yml` and paste in the file's contents.
3. Open the repo's Actions tab. The "Build APK" run starts automatically (or press Run workflow). It takes about 5 to 8 minutes.
4. Open the finished run, download the `obd-logger-apk` artifact (a zip), unzip it, and copy `app-debug.apk` to your phone.
5. On the phone, open the APK and allow "Install unknown apps" when asked.
If the build fails, open the failed step, copy the red error text and send it to me.

## Or build with Android Studio
File > Open > select this folder, wait for Gradle sync, then Build > Build APK(s).

## Use
1. Plug the ELM327 into the car's OBD port, ignition on.
2. Pair it in Android Settings > Bluetooth (PIN usually 1234 or 0000).
3. Open the app > Connect > tap the adapter. Logging to the database starts automatically; each connection is one "trip".
4. Tabs: Live gauges, History (charts, trip stats, CSV export), Diag, Car.
   Diag has: VIN, check-engine-light status and extra info (run time, distance since codes cleared), stored/pending/permanent codes with clear,
   freeze frame, a scan of ALL modules (ABS, airbag, gearbox...) for fault codes on CAN cars, and a raw command terminal for make-specific data.
5. "Demo mode" runs a simulated car so you can try the app without hardware.

## Car profile and fuel estimates
- On first launch the app asks for make, model, year, engine size, fuel type and (optionally) fuel price. Edit it later in the Car tab.
- Each trip stores a snapshot of the car, so history stays correct if you change cars.
- Fuel use is estimated from the engine fuel-rate PID if the car reports it, else the MAF sensor, else MAP + RPM + intake temp with your engine size.
- Per trip you get distance, duration, average/max speed, idle time, fuel used, L/100 km, km/L, cost and CO2.
- Accuracy: reasonable for petrol, approximate otherwise. Diesels run lean, so MAF/MAP-based figures overstate use.
  Calibrate by comparing with a full-tank-to-full-tank figure.

## Extending
- Add live-data parameters in `Obd.kt` (`Pids.all`). They show up if the car supports them.
- Data lives in `obd.db` (tables Trip, Sample, DtcLog).

## Limits
- Module scan needs 11-bit CAN (most cars from about 2008). Older K-line cars (some older Maruti/Hyundai/Tata) only get the standard engine tools.
- Other modules' fault codes are listed by address because names differ by make. Live data from ABS/airbag/gearbox is proprietary per make:
  use the terminal (e.g. UDS `22xxxx`) if you know the codes for your car.
- Cheap ELM327 clones can be unreliable on multi-frame replies (VIN, module scan).
