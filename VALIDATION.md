Validation status of the USB-specific revision:
- All seven Java source files parsed by the JDK Java compiler frontend (syntax only).
- AndroidManifest.xml parsed as XML.
- Added UsbLibraryAccess.java, USB status check, safe rescan behavior and HTTP 503 on unreadable book files.
- Build validation requires the Android SDK, Gradle and generated wrapper or local Gradle (not available here).
- Runtime behavior with removable USB depends on the Android 10 firmware and must be tested on an S905 box.
