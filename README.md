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

The Chat tab uses Firebase AI Logic with Gemini 3.8 Flash. In the Firebase console, open **AI Services > AI Logic**, complete **Get started**, and enable the Gemini Developer API for the project. If App Check is enforced, configure its debug provider and register the device's debug token for local testing; configure a production attestation provider before release.

Chat sends the user's message plus monthly spending totals, category aggregates and savings-goal summaries to Gemini. Transaction notes, account numbers and credentials are not included in the financial context. Review Firebase AI Logic quotas, pricing and data-governance settings before wider use. Statement import, admin tools and automated savings recommendations remain later milestones. A Firebase project is required for live sign-in and CRUD testing on a device.