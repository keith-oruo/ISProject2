import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import { getApp, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import nodemailer from "nodemailer";

initializeApp();

const REGION = "us-central1";
const darajaConsumerKey = defineSecret("DARAJA_CONSUMER_KEY");
const darajaConsumerSecret = defineSecret("DARAJA_CONSUMER_SECRET");
const darajaShortCode = defineSecret("DARAJA_SHORT_CODE");
const darajaPassKey = defineSecret("DARAJA_PASS_KEY");
const emailMfaSecret = defineSecret("EMAIL_MFA_SECRET");
const smtpHost = defineSecret("SMTP_HOST");
const smtpPort = defineSecret("SMTP_PORT");
const smtpUser = defineSecret("SMTP_USER");
const smtpPassword = defineSecret("SMTP_PASSWORD");
const smtpFrom = defineSecret("SMTP_FROM");
const darajaBaseUrl = defineString("DARAJA_BASE_URL", {
  default: "https://sandbox.safaricom.co.ke",
});
const firestore = getFirestore();
const emailMfaSecrets = [
  emailMfaSecret,
  smtpHost,
  smtpPort,
  smtpUser,
  smtpPassword,
  smtpFrom,
];

type JsonObject = Record<string, unknown>;

type AuthContext = {
  uid: string;
  token: Record<string, unknown>;
};

function isEmailMfaEnabled(token: Record<string, unknown>): boolean {
  return token.emailMfaEnabled === true;
}

function requireEmailMfaComplete(auth: AuthContext): void {
  if (!isEmailMfaEnabled(auth.token)) return;
  const verifiedAt = auth.token.emailMfaVerifiedAt;
  const authTime = auth.token.auth_time;
  if (
    typeof verifiedAt !== "number" ||
    typeof authTime !== "number" ||
    verifiedAt < authTime
  ) {
    throw new HttpsError(
      "unauthenticated",
      "Complete email verification before using this service.",
    );
  }
}

function requireRecentAuthentication(auth: AuthContext): void {
  const authTime = auth.token.auth_time;
  if (
    typeof authTime !== "number" ||
    Date.now() - authTime * 1000 > 5 * 60 * 1000
  ) {
    throw new HttpsError(
      "failed-precondition",
      "Sign in again before changing email two-factor authentication.",
    );
  }
}

function challengeHash(uid: string, code: string): Buffer {
  return createHmac("sha256", emailMfaSecret.value())
    .update(`${uid}:${code}`)
    .digest();
}

async function sendEmailMfaChallenge(uid: string, email: string): Promise<void> {
  const code = randomInt(100_000, 1_000_000).toString();
  const reference = firestore.collection("emailMfaChallenges").doc(uid);
  const now = Date.now();
  const canSend = await firestore.runTransaction(async (transaction) => {
    const saved = await transaction.get(reference);
    if (saved.exists && now - (saved.get("sentAt") as number) < 30_000) {
      return false;
    }
    transaction.set(reference, {
      codeHash: challengeHash(uid, code).toString("hex"),
      email,
      sentAt: now,
      expiresAt: now + 10 * 60 * 1000,
      attempts: 0,
    });
    return true;
  });
  if (!canSend) {
    throw new HttpsError(
      "resource-exhausted",
      "Wait 30 seconds before requesting another verification email.",
    );
  }

  const port = Number(smtpPort.value());
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new HttpsError("failed-precondition", "Email delivery is not configured correctly.");
  }
  const transporter = nodemailer.createTransport({
    host: smtpHost.value(),
    port,
    secure: port === 465,
    auth: { user: smtpUser.value(), pass: smtpPassword.value() },
  });
  try {
    await transporter.sendMail({
      from: smtpFrom.value(),
      to: email,
      subject: "Your PesaPath verification code",
      text: `Your PesaPath verification code is ${code}. It expires in 10 minutes. If you did not request this code, you can ignore this email.`,
    });
  } catch (error) {
    await reference.delete();
    logger.error("Could not send an email MFA challenge.", error);
    throw new HttpsError("unavailable", "Could not send a verification email. Try again later.");
  }
}

async function startEmailMfaChallenge(
  auth: AuthContext,
  purpose: "enroll" | "sign-in",
): Promise<{ emailHint: string }> {
  const user = await getAuth().getUser(auth.uid);
  const factor = firestore.collection("emailMfaFactors").doc(auth.uid);
  const enrolledFactor = purpose === "sign-in" ? await factor.get() : null;
  const email = purpose === "sign-in"
    ? enrolledFactor?.get("email")
    : user.emailVerified ? user.email : null;
  if (typeof email !== "string" || !email) {
    throw new HttpsError(
      "failed-precondition",
      "Verify your account email before enabling email two-factor authentication.",
    );
  }
  if (purpose === "enroll") {
    requireRecentAuthentication(auth);
  } else if (!isEmailMfaEnabled(auth.token)) {
    throw new HttpsError("failed-precondition", "Email two-factor authentication is not enabled.");
  }
  await sendEmailMfaChallenge(auth.uid, email);
  const [name, domain] = email.split("@");
  return { emailHint: `${name.slice(0, 1)}•••@${domain}` };
}

async function verifyEmailMfaChallenge(
  auth: AuthContext,
  code: string,
  purpose: "enroll" | "sign-in",
): Promise<void> {
  const reference = firestore.collection("emailMfaChallenges").doc(auth.uid);
  const now = Date.now();
  const verifiedEmail = await firestore.runTransaction(async (transaction) => {
    const challenge = await transaction.get(reference);
    if (!challenge.exists) return null;
    const data = challenge.data();
    const attempts = typeof data?.attempts === "number" ? data.attempts : 0;
    const savedHash = typeof data?.codeHash === "string" ? data.codeHash : "";
    const email = typeof data?.email === "string" ? data.email : null;
    const expired = typeof data?.expiresAt !== "number" || data.expiresAt <= now;
    if (expired || attempts >= 5) {
      transaction.delete(reference);
      return null;
    }
    const actualHash = challengeHash(auth.uid, code);
    const expectedHash = Buffer.from(savedHash, "hex");
    const matches =
      expectedHash.length === actualHash.length &&
      timingSafeEqual(actualHash, expectedHash);
    if (!matches) {
      transaction.update(reference, { attempts: attempts + 1 });
      return null;
    }
    transaction.delete(reference);
    return email;
  });
  if (!verifiedEmail) {
    throw new HttpsError(
      "permission-denied",
      "The verification code is invalid or expired. Request a new code and try again.",
    );
  }

  if (purpose === "sign-in" && !isEmailMfaEnabled(auth.token)) {
    throw new HttpsError("failed-precondition", "Email two-factor authentication is not enabled.");
  }
  const user = await getAuth().getUser(auth.uid);
  const claims = { ...(user.customClaims ?? {}) };
  if (purpose === "enroll") {
    if (!user.email || !user.emailVerified || user.email !== verifiedEmail) {
      throw new HttpsError(
        "failed-precondition",
        "Your verified account email changed. Request a new code and try again.",
      );
    }
    claims.emailMfaEnabled = true;
    await firestore.collection("emailMfaFactors").doc(auth.uid).set({
      email: verifiedEmail,
      enrolledAt: FieldValue.serverTimestamp(),
    });
  }
  claims.emailMfaVerifiedAt = Math.floor(Date.now() / 1000);
  await getAuth().setCustomUserClaims(auth.uid, claims);
}

export const beginEmailMfaEnrollment = onCall(
  { region: REGION, maxInstances: 10, secrets: emailMfaSecrets },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Sign in before enabling email two-factor authentication.");
    }
    requireEmailMfaComplete(request.auth);
    return startEmailMfaChallenge(request.auth, "enroll");
  },
);

export const beginEmailMfaSignIn = onCall(
  { region: REGION, maxInstances: 10, secrets: emailMfaSecrets },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to continue.");
    return startEmailMfaChallenge(request.auth, "sign-in");
  },
);

export const verifyEmailMfa = onCall(
  { region: REGION, maxInstances: 10, secrets: emailMfaSecrets },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to continue.");
    const data = objectValue(request.data);
    const code = typeof data?.code === "string" ? data.code.trim() : "";
    const purpose = data?.purpose;
    if (!/^\d{6}$/.test(code) || (purpose !== "enroll" && purpose !== "sign-in")) {
      throw new HttpsError("invalid-argument", "Enter the 6-digit email verification code.");
    }
    if (purpose === "enroll") {
      requireEmailMfaComplete(request.auth);
      requireRecentAuthentication(request.auth);
    }
    await verifyEmailMfaChallenge(request.auth, code, purpose);
    return { verified: true };
  },
);

export const disableEmailMfa = onCall(
  { region: REGION, maxInstances: 10 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to continue.");
    requireEmailMfaComplete(request.auth);
    requireRecentAuthentication(request.auth);
    const user = await getAuth().getUser(request.auth.uid);
    const claims = { ...(user.customClaims ?? {}) };
    delete claims.emailMfaEnabled;
    delete claims.emailMfaVerifiedAt;
    await getAuth().setCustomUserClaims(request.auth.uid, claims);
    await firestore.collection("emailMfaFactors").doc(request.auth.uid).delete();
    await firestore.collection("emailMfaChallenges").doc(request.auth.uid).delete();
    return { disabled: true };
  },
);

function objectValue(value: unknown): JsonObject | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  return value as JsonObject;
}

function normalizePhone(value: string): string | null {
  const compact = value.trim().replace(/[\s()+-]/g, "");
  const local = compact.match(/^0([17]\d{8})$/);
  if (local) return `254${local[1]}`;
  if (/^254[17]\d{8}$/.test(compact)) return compact;
  return null;
}

function timestamp(): string {
  const parts: Record<string, string> = {};
  for (const part of new Intl.DateTimeFormat("en-GB", {
      timeZone: "Africa/Nairobi",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    }).formatToParts(new Date())) {
    parts[part.type] = part.value;
  }
  return ["year", "month", "day", "hour", "minute", "second"]
    .map((part) => parts[part] ?? "")
    .join("");
}

function baseUrl(): string {
  const value = darajaBaseUrl.value().replace(/\/+$/, "");
  if (!/^https:\/\/(sandbox\.safaricom\.co\.ke|api\.safaricom\.co\.ke)$/.test(value)) {
    throw new HttpsError("failed-precondition", "The Daraja API address is not configured correctly.");
  }
  return value;
}

function callbackUrl(): string {
  const projectId = getApp().options.projectId ?? process.env.GCLOUD_PROJECT;
  if (!projectId) throw new HttpsError("internal", "Firebase project configuration is missing.");
  return `https://${REGION}-${projectId}.cloudfunctions.net/darajaCallback`;
}

async function responseJson(response: Response): Promise<JsonObject> {
  try {
    return objectValue(await response.json()) ?? {};
  } catch {
    throw new HttpsError("unavailable", "Safaricom returned an unreadable response.");
  }
}

async function accessToken(): Promise<string> {
  const credentials = Buffer.from(
    `${darajaConsumerKey.value()}:${darajaConsumerSecret.value()}`,
  ).toString("base64");
  const response = await fetch(
    `${baseUrl()}/oauth/v1/generate?grant_type=client_credentials`,
    {
      headers: { Authorization: `Basic ${credentials}`, Accept: "application/json" },
      signal: AbortSignal.timeout(15_000),
    },
  );
  const data = await responseJson(response);
  if (!response.ok || typeof data.access_token !== "string") {
    throw new HttpsError("unavailable", "Could not authenticate with Safaricom.");
  }
  return data.access_token;
}

function darajaPassword(requestTimestamp: string): string {
  return Buffer.from(
    `${darajaShortCode.value()}${darajaPassKey.value()}${requestTimestamp}`,
  ).toString("base64");
}

async function queryDarajaStatus(token: string, checkoutRequestId: string): Promise<JsonObject> {
  const requestTimestamp = timestamp();
  const response = await fetch(`${baseUrl()}/mpesa/stkpushquery/v1/query`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      BusinessShortCode: darajaShortCode.value(),
      Password: darajaPassword(requestTimestamp),
      Timestamp: requestTimestamp,
      CheckoutRequestID: checkoutRequestId,
    }),
    signal: AbortSignal.timeout(15_000),
  });
  const data = await responseJson(response);
  if (!response.ok) throw new Error("Safaricom status verification failed.");
  return data;
}

export const createMpesaPayment = onCall(
  {
    region: REGION,
    maxInstances: 10,
    secrets: [
      darajaConsumerKey,
      darajaConsumerSecret,
      darajaShortCode,
      darajaPassKey,
    ],
  },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in before starting a payment.");
    requireEmailMfaComplete(request.auth);

    const data = objectValue(request.data);
    const phone = typeof data?.phone === "string" ? normalizePhone(data.phone) : null;
    const amountCents = data?.amountCents;
    if (!phone) throw new HttpsError("invalid-argument", "Enter a valid Kenyan mobile number.");
    if (
      typeof amountCents !== "number" ||
      !Number.isSafeInteger(amountCents) ||
      amountCents < 100 ||
      amountCents > 25_000_000 ||
      amountCents % 100 !== 0
    ) {
      throw new HttpsError(
        "invalid-argument",
        "Enter a whole amount from KES 1 to KES 250,000.",
      );
    }

    const transaction = firestore
      .collection("users")
      .doc(request.auth.uid)
      .collection("mpesaTransactions")
      .doc();
    await transaction.set({
      phone,
      amountCents,
      status: "pending",
      createdAt: Date.now(),
    });

    let token: string;
    try {
      token = await accessToken();
    } catch (error) {
      logger.error("Daraja authentication failed", error);
      await transaction.update({
        status: "failed",
        resultDesc: "The payment provider could not be reached.",
        updatedAt: Date.now(),
      });
      throw error instanceof HttpsError
        ? error
        : new HttpsError("unavailable", "Could not connect to Safaricom. Try again later.");
    }

    const requestTimestamp = timestamp();
    const payload = {
      BusinessShortCode: darajaShortCode.value(),
      Password: darajaPassword(requestTimestamp),
      Timestamp: requestTimestamp,
      TransactionType: "CustomerPayBillOnline",
      Amount: amountCents / 100,
      PartyA: phone,
      PartyB: darajaShortCode.value(),
      PhoneNumber: phone,
      CallBackURL: callbackUrl(),
      AccountReference: transaction.id,
      TransactionDesc: "PesaPath payment",
    };

    let response: Response;
    try {
      response = await fetch(`${baseUrl()}/mpesa/stkpush/v1/processrequest`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(20_000),
      });
    } catch (error) {
      logger.error("Daraja STK request outcome is unknown", error);
      throw new HttpsError(
        "unavailable",
        "The payment request could not be confirmed. Check payment history before trying again.",
      );
    }

    const result = await responseJson(response);
    const checkoutRequestId =
      typeof result.CheckoutRequestID === "string" ? result.CheckoutRequestID : null;
    const merchantRequestId =
      typeof result.MerchantRequestID === "string" ? result.MerchantRequestID : null;
    const responseDesc =
      typeof result.ResponseDescription === "string"
        ? result.ResponseDescription
        : "Safaricom could not start the payment.";

    if (!response.ok || result.ResponseCode !== "0" || !checkoutRequestId) {
      await transaction.update({
        status: "failed",
        resultDesc: responseDesc,
        updatedAt: Date.now(),
      });
      throw new HttpsError("failed-precondition", responseDesc);
    }

    await transaction.update({
      checkoutRequestId,
      merchantRequestId,
      resultDesc: responseDesc,
    });

    return {
      transactionId: transaction.id,
      checkoutRequestId,
      responseDesc,
    };
  },
);

export const darajaCallback = onRequest(
  {
    region: REGION,
    maxInstances: 10,
    secrets: [
      darajaConsumerKey,
      darajaConsumerSecret,
      darajaShortCode,
      darajaPassKey,
    ],
  },
  async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }

    const body = objectValue(request.body);
    const stkCallback = objectValue(objectValue(body?.Body)?.stkCallback);
    const checkoutRequestId =
      typeof stkCallback?.CheckoutRequestID === "string"
        ? stkCallback.CheckoutRequestID
        : null;
    const callbackResultCode =
      stkCallback?.ResultCode === undefined ? null : String(stkCallback.ResultCode);
    if (!stkCallback || !checkoutRequestId || callbackResultCode === null) {
      response.status(400).send("Invalid callback");
      return;
    }

    const matches = await firestore
      .collectionGroup("mpesaTransactions")
      .where("checkoutRequestId", "==", checkoutRequestId)
      .limit(1)
      .get();
    if (matches.empty) {
      logger.warn("Daraja callback did not match a payment", { checkoutRequestId });
      response.status(503).send("Payment is not ready for callback processing");
      return;
    }

    const transaction = matches.docs[0];
    const saved = transaction.data();
    if (saved.status === "completed" || saved.status === "failed") {
      response.status(200).json({ ResultCode: 0, ResultDesc: "Callback already processed" });
      return;
    }

    let verifiedStatus: JsonObject;
    try {
      verifiedStatus = await queryDarajaStatus(await accessToken(), checkoutRequestId);
    } catch (error) {
      logger.error("Could not verify Daraja callback", { checkoutRequestId, error });
      response.status(503).send("Payment verification is temporarily unavailable");
      return;
    }

    const verifiedResultCode =
      verifiedStatus.ResultCode === undefined ? null : String(verifiedStatus.ResultCode);
    if (verifiedResultCode !== callbackResultCode) {
      logger.warn("Daraja callback and status query do not match", { checkoutRequestId });
      response.status(503).send("Payment status is not yet verified");
      return;
    }

    const callbackMetadata = objectValue(stkCallback.CallbackMetadata);
    const callbackItems = Array.isArray(callbackMetadata?.Item) ? callbackMetadata.Item : [];
    const metadata = new Map<string, unknown>();
    for (const item of callbackItems) {
      const value = objectValue(item);
      if (typeof value?.Name === "string") metadata.set(value.Name, value.Value);
    }

    const completed = callbackResultCode === "0";
    if (completed) {
      const callbackAmount = Number(metadata.get("Amount"));
      const callbackPhone = normalizePhone(String(metadata.get("PhoneNumber") ?? ""));
      const receiptNumber = metadata.get("MpesaReceiptNumber");
      if (
        !Number.isFinite(callbackAmount) ||
        callbackAmount * 100 !== saved.amountCents ||
        callbackPhone !== saved.phone ||
        typeof receiptNumber !== "string"
      ) {
        logger.error("Verified Daraja callback did not match the saved payment", {
          checkoutRequestId,
        });
        response.status(409).send("Payment details did not match");
        return;
      }

      await transaction.ref.update({
        status: "completed",
        resultCode: callbackResultCode,
        resultDesc: String(stkCallback.ResultDesc ?? "Payment completed"),
        receiptNumber,
        paidAt: FieldValue.serverTimestamp(),
        updatedAt: Date.now(),
      });
    } else {
      await transaction.ref.update({
        status: "failed",
        resultCode: callbackResultCode,
        resultDesc: String(stkCallback.ResultDesc ?? "Payment was not completed"),
        updatedAt: Date.now(),
      });
    }

    response.status(200).json({ ResultCode: 0, ResultDesc: "Callback processed" });
  },
);
