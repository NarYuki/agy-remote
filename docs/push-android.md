# Push notifications on Android (FCM)

Android notifications use the same end-to-end scheme as iOS: the phone makes a 256-bit key,
hands it to each computer over the encrypted P2P link (`notifications.register`, with
`"platform": "fcm"`), and `agy-connector` seals every notification with ChaCha20-Poly1305 into a
fixed-size 540-byte blob. The push relay forwards that blob to Firebase Cloud Messaging as a
**data-only, high-priority** message, and the app's `AgyMessagingService` decrypts it on the phone
and shows the notification itself. Google sees the FCM token, the time and the opaque blob, but no
text.

Nothing contacts Google until notifications are turned on: FCM auto-init is off in the manifest and
is turned on only when you enable notifications. Turning them off deletes the token again.

You need to set up three things: a Firebase project, `google-services.json` in the app, and the FCM
secrets on the relay.

## 1. Create the Firebase project

1. Open <https://console.firebase.google.com>, then **Add project** (Google Analytics is not
   needed, so leave it off).
2. **Add app → Android**, with the package name `moe.n4tsu.agyremote` (or your own `applicationId` if
   you changed it). The SHA-1 is not needed for FCM.
3. Download **google-services.json**.

## 2. Add google-services.json to the app

Copy it to `mobile/android/app/google-services.json`.

The file is listed in `.gitignore`, so **never commit it**. When it is missing, the build still works:
Gradle prints `AGY: app/google-services.json not found, building without Firebase` and leaves out
the google-services plugin. That build runs normally, but turning notifications on shows "Couldn't
turn on notifications: Firebase is not configured in this build".

```sh
cd mobile/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

For CI, write the file from a secret before the build. Don't store it in the repository.

## 3. Give the relay a service-account key

1. In the Firebase console: **Project settings → Service accounts → Generate new private key**.
   This downloads a JSON key. Keep it outside the repository, because it can send pushes to your app.
2. Copy three of its fields into the relay's secrets file, which also lives outside the repository,
   next to the APNs secrets:

   ```json
   {
     "APNS_KEY_P8": "…", "APNS_KEY_ID": "…", "APNS_TEAM_ID": "…",
     "FCM_PROJECT_ID": "<project_id>",
     "FCM_CLIENT_EMAIL": "<client_email>",
     "FCM_PRIVATE_KEY": "<private_key, exactly as in the JSON, \\n escapes included>"
   }
   ```

   You can also set them one at a time with `npx cf workers secrets put FCM_PRIVATE_KEY` and so on.
   The relay accepts the private key with real newlines or with `\n` escapes.

The Firebase Cloud Messaging API (V1) is enabled by default for new projects. If sends fail with
`PERMISSION_DENIED`, enable it at **Google Cloud console → APIs & Services**.

## 4. Deploy the relay

```sh
cd relay
npm install
npm test
npx cf auth login
npx cf deploy --secrets-file /path/outside/repo/secrets.json
```

The relay signs an RS256 JWT with the service-account key, exchanges it for an OAuth access token
at `oauth2.googleapis.com`, and keeps that token in memory for about 55 minutes. A request without
`platform` goes to APNs (the connector leaves it out for iOS phones).

If you run your own relay, point the connectors at it:

```sh
agy-connector set-relay https://your-relay.example.workers.dev
```

## Checking it

- `cargo test -p agy-connector` (in `server/`) seals a fixed test vector into
  `server/agy-connector/tests/push_vector.txt`. Run it with `AGY_WRITE_PUSH_VECTOR=1` to regenerate the file.
- `./gradlew :app:testDebugUnitTest` (in `mobile/android/`) decrypts that same blob in Kotlin
  (`PushCryptoTest`).
- `npm test` (in `relay/`) covers the FCM request shape, the OAuth token cache, and the mapping of
  `UNREGISTERED` to a 410 response.
- End to end: turn notifications on in the app, start a turn on the computer, then lock the phone.
  You should get "Finished", "Failed", "Needs Approval" (with Allow/Deny) and "Closed in the agy
  CLI" notifications, each on its own channel under the app's system notification settings.
