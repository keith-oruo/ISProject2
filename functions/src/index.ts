import { getApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";

initializeApp();

const REGION = "us-central1";
const darajaConsumerKey = defineSecret("DARAJA_CONSUMER_KEY");
const darajaConsumerSecret = defineSecret("DARAJA_CONSUMER_SECRET");
const darajaShortCode = defineSecret("DARAJA_SHORT_CODE");
const darajaPassKey = defineSecret("DARAJA_PASS_KEY");
const darajaBaseUrl = defineString("DARAJA_BASE_URL", {
  default: "https://sandbox.safaricom.co.ke",
});
const firestore = getFirestore();

type JsonObject = Record<string, unknown>;

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
