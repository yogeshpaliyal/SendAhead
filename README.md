# WA Scheduler

Open-source, fully offline WhatsApp message scheduler for Android, with attachments.

- Schedule text, images, videos, PDFs or any file to a WhatsApp / WhatsApp Business number
- No internet permission, no account, no server: messages and files stay on the phone
- Uses an Accessibility Service only to tap WhatsApp's **Send** button at the scheduled time

## Download

The latest build from `master` is always at
[Releases → latest master](../../releases/tag/latest-master) (debug-signed, installs over previous builds).

## Setup

1. Install the APK.
2. Open the app and tap **Enable** → turn on *WA Scheduler – auto send* in Accessibility settings.
   On Android 13+ a sideloaded app's accessibility toggle may be greyed out. Open
   *App info → ⋮ → Allow restricted settings* first.
3. Allow **Alarms & reminders** if asked, so messages go out on time.
4. Tap **+**, enter the number with country code (e.g. `919876543210`), message, attachments, date and time.
   Use **Test send now** to check that it works on your phone.

## How it works

1. `AlarmManager` fires an exact alarm at the scheduled time.
2. The app opens WhatsApp with the chat prefilled (`api.whatsapp.com/send` for text,
   `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with a `jid` extra for attachments).
3. The accessibility service finds the Send button (`<pkg>:id/send`, falling back to a "Send" label) and taps it,
   then returns to the home screen. A notification reports sent / failed.

## Limitations

- The phone must be on, and **unlocked or without a secure lock screen**. WhatsApp can't be driven behind a PIN/pattern lock.
  The app wakes the screen, but it can't unlock it.
- Battery savers (especially on Xiaomi, Oppo, Vivo and similar phones) can kill the accessibility service. Set the app's battery usage to *Unrestricted*.
- WhatsApp UI updates can break the Send button lookup. Please open an issue if that happens.
- The number must be on WhatsApp. The target chat doesn't need to be in your contacts.

## Build

```
./gradlew assembleDebug
```

## License

MIT
