# PesaPath: first milestone

This Android/Kotlin prototype implements the first part of the proposed financial assistant. It includes email/password registration, login and password reset; a dashboard with this month's spending and category breakdown; and create, read, edit and delete for manual transactions and savings goals. Money is stored in integer cents (KES) to avoid rounding errors. The UI follows the login, dashboard, add-transaction and savings-goal wireframes in `IS diagrams.docx`.

## Firebase setup

1. Create a Firebase project and register an Android app with package name `ke.co.pesapath`.
2. Download its `google-services.json` into `app/`. This file is intentionally ignored by Git. Sync/rebuild the Gradle project afterward.
3. Enable **Authentication > Sign-in method > Email/Password** in the Firebase console.
4. Create a **Cloud Firestore** database and publish the contents of `firestore.rules` under **Firestore Database > Rules** before adding user data. Do not leave the database in test mode.

Only authenticated users can read or change their own `users/{uid}/transactions` and `users/{uid}/goals` documents. Passwords are handled by Firebase Authentication and are not stored in Firestore. Without a valid Firebase configuration the app shows a setup message on the login screen.

## Build

Use JDK 17, Android SDK platform 36 and Build Tools 36.0.0. Run `:app:assembleDebug` with Gradle (or use Android Studio's Build APK command). The generated APK is in `app/build/outputs/apk/debug/`.

The chat assistant, statement import, admin console, threshold notifications and automated savings recommendations belong to later milestones; the Chat tab explicitly indicates this. A Firebase project is required for live sign-in and CRUD testing on a device.