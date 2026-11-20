<img src="docs/icon.png" width="96" alt="Sendahead icon" />

# Sendahead

Write it now, send it ahead of time. An open-source, fully offline scheduler for WhatsApp messages on Android, with attachments.

> Sendahead is an independent project. It is not affiliated with, endorsed by or sponsored by WhatsApp or Meta.
> WhatsApp is a trademark of WhatsApp LLC.

- Schedule text, images, videos, PDFs or any file to a WhatsApp / WhatsApp Business number
- Send to **groups and saved contacts by name**, with a picker that uses WhatsApp's own chat list
- Optional **auto-unlock** with your PIN/password (off by default)
- No internet permission, no account, no server: messages and files stay on the phone
- Uses an Accessibility Service only to tap WhatsApp's **Send** button at the scheduled time

## Download

The latest build from `master` is always at
[Releases → latest master](../../releases/tag/latest-master) (debug-signed, installs over previous builds).

## Setup

1. Install the APK.
2. Open the app and tap **Enable** → turn on *Sendahead auto-send* in Accessibility settings.
   On Android 13+ a sideloaded app's accessibility toggle may be greyed out. Open
   *App info → ⋮ → Allow restricted settings* first.
3. Allow **Alarms & reminders** if asked, so messages go out on time.
4. Tap **+**, enter the number with country code (e.g. `919876543210`), message, attachments, date and time.
   Use **Test send now** to check that it works on your phone.

## Groups / contacts by name

Choose **Group / contact** and tap **Pick**. WhatsApp's "Send to" list opens. Tap the chat and you're brought back
with its name filled in (nothing is sent). At send time the app opens the same list, searches for that exact name,
selects it and taps Send. Chat names must be unique: if two chats share a name, the first match is used.

## Auto-unlock (optional, advanced)

Menu → **Auto-unlock**. If the phone is locked with a PIN or password when a message is due, the accessibility service
types it on the lock screen. The secret is AES-GCM encrypted with a key held in the Android Keystore, and app backups
are disabled. It makes only **one attempt per message**, and if that fails, auto-unlock switches itself off so a wrong
PIN can never lock you out. Patterns and fingerprint-only locks are not supported. Only use this on your own phone.

## How it works

1. `AlarmManager` fires an exact alarm at the scheduled time.
2. The app opens WhatsApp with the chat prefilled (`api.whatsapp.com/send` for text,
   `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with a `jid` extra for attachments).
3. The accessibility service finds the Send button (`<pkg>:id/send`, falling back to a "Send" label) and taps it,
   then returns to the home screen. A notification reports sent / failed.

## Limitations

- **Lock screen:** with *None* or *Swipe* the app turns the screen on, dismisses the lock screen and sends automatically.
  With a PIN/password and auto-unlock off, Android shows the unlock prompt and the message is sent if you unlock within
  about 45 seconds. Otherwise it's marked failed and you can reschedule it.
- Battery savers (especially on Xiaomi, Oppo, Vivo and similar phones) can kill the accessibility service. Set the app's battery usage to *Unrestricted*.
- WhatsApp UI updates can break the Send button lookup. Please open an issue if that happens.
- The number must be on WhatsApp. The target chat doesn't need to be in your contacts.

## Releases

**Every push to `master`** that touches the app builds a debug APK and replaces the
[Latest master](../../releases/tag/latest-master) pre-release. It installs as *Sendahead Debug*
(`com.yogeshpaliyal.sendahead.debug`), next to the release app.

**To publish a release**, open Actions > **Release** > Run workflow and pick `patch`, `minor` or `major`. It:

1. bumps `versionName`/`versionCode` in `app/build.gradle.kts`
2. builds the signed APK and AAB, and checks they aren't debug-signed
3. only then commits the bump, tags it and pushes both in one atomic push
4. publishes a GitHub release with notes built from the app's commits since the last release,
   the signing-certificate SHA-256 (for AppVerifier) and file checksums
5. announces it in Discussions (Announcements category), plus Telegram, Bluesky and Mastodon if
   their secrets are set, and writes an XDA post to copy from the run summary

A daily **Stats snapshot** saves download counts and stars to the `stats` branch.

### One-time setup

1. On your own computer, run `./scripts/create-release-keystore.sh`. It creates
   `~/Documents/Sendahead-signing/` (keystore, passwords, fingerprints) and, if the GitHub CLI is
   logged in, uploads the secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
   `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Back that folder up: losing the key means
   existing installs can't be updated.
2. Optional: enable Discussions with an Announcements category; add `TELEGRAM_BOT_TOKEN` +
   `TELEGRAM_CHAT_ID`, `BLUESKY_HANDLE` + `BLUESKY_APP_PASSWORD`, `MASTODON_INSTANCE` +
   `MASTODON_TOKEN` secrets; a `TRAFFIC_TOKEN` secret for page-view stats; and a `PLAY_URL`
   repository variable once the app is on Google Play.

For a signed release build on your machine, copy `keystore.properties` from the signing folder
into the repo root (it's git-ignored) and run `./gradlew assembleRelease`.

## Build

```
./gradlew assembleDebug
```

## License

MIT
