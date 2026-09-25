import { describe, expect, it } from "vitest";
import {
  Close,
  MAX_FRAME_BYTES,
  codeHashOf,
  frameTooLarge,
  isEndFrame,
  normaliseCode,
  parseJoinCode,
  peekHelloSessionId,
  safeCloseCode,
  sha256Hex,
  tokenMatches,
  validateHello,
} from "../src/protocol";
import { parseEcPublicJwk } from "../src/keys";
import testPublicJwk from "./license-test.public.jwk.json";
import { CODE, helloFrame, licenseJwt } from "./helpers";

const JWK = parseEcPublicJwk(JSON.stringify(testPublicJwk))!;
const SID = "0f6f7c2a-9b2e-4c4e-8d1a-2b3c4d5e6f70";
const HASH = "a".repeat(64);

describe("normaliseCode", () => {
  it("uppercases, strips dashes and spaces, and reads I/L as 1 and O as 0", () => {
    expect(normaliseCode("7h3q-k2xm")).toBe("7H3QK2XM");
    expect(normaliseCode(" 7H3Q K2XM ")).toBe("7H3QK2XM");
    expect(normaliseCode("il0o-abcd")).toBe("1100ABCD");
    expect(normaliseCode("ILOO-ABCD")).toBe("1100ABCD");
  });

  it("rejects anything that is not eight Crockford characters", () => {
    expect(normaliseCode("7H3Q-K2X")).toBeNull();
    expect(normaliseCode("7H3Q-K2XMM")).toBeNull();
    expect(normaliseCode("7H3Q-K2XU")).toBeNull(); // U is not in the alphabet
    expect(normaliseCode("")).toBeNull();
    expect(normaliseCode("7H3Q_K2XM")).toBeNull();
  });

  it("hashes the way the app does: sha256(\"bnm-lab-support|\" + code)", async () => {
    expect(await codeHashOf("7H3QK2XM")).toBe(await sha256Hex("bnm-lab-support|7H3QK2XM"));
    expect(await sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});

describe("parseJoinCode / peekHelloSessionId", () => {
  it("reads a join frame and normalises its code", () => {
    expect(parseJoinCode(JSON.stringify({ t: "join", code: "7h3q-k2xm" }))).toBe("7H3QK2XM");
    expect(parseJoinCode(JSON.stringify({ t: "hello", code: CODE }))).toBeNull();
    expect(parseJoinCode(JSON.stringify({ t: "join", code: 12345678 }))).toBeNull();
    expect(parseJoinCode(JSON.stringify({ t: "join", code: "x".repeat(40) }))).toBeNull();
    expect(parseJoinCode("[]")).toBeNull();
    expect(parseJoinCode("{not json")).toBeNull();
  });

  it("peeks only the session id of a hello, verifying nothing", () => {
    expect(peekHelloSessionId(JSON.stringify({ t: "hello", session_id: SID }))).toBe(SID);
    expect(peekHelloSessionId(JSON.stringify({ t: "hello", session_id: "nope" }))).toBeNull();
    expect(peekHelloSessionId(JSON.stringify({ t: "join", session_id: SID }))).toBeNull();
    expect(peekHelloSessionId("garbage")).toBeNull();
  });
});

describe("validateHello", () => {
  it("accepts a well-formed hello with a genuine licence", async () => {
    const v = await validateHello(JSON.stringify(helloFrame(await licenseJwt(), SID, HASH, 14400)), JWK);
    expect(v).toEqual({ ok: true, hello: { sessionId: SID, codeHash: HASH, expiresInS: 14400 } });
  });

  it("names the field that is wrong", async () => {
    const jwt = await licenseJwt();
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ t: "join" }, "bad_hello"],
      [{ v: 2 }, "bad_version"],
      [{ v: "1" }, "bad_version"],
      [{ jwt: 42 }, "bad_jwt"],
      [{ jwt: jwt.slice(0, -4) + "AAAA" }, "bad_jwt"],
      [{ jwt: await licenseJwt({ iss: "other" }) }, "bad_jwt"],
      [{ session_id: "not-a-uuid" }, "bad_session_id"],
      [{ code_hash: "A".repeat(64) }, "bad_code_hash"], // must be lowercase hex
      [{ code_hash: "a".repeat(63) }, "bad_code_hash"],
      [{ expires_in_s: 0 }, "bad_expiry"],
      [{ expires_in_s: 86401 }, "bad_expiry"],
      [{ expires_in_s: 1.5 }, "bad_expiry"],
      [{ expires_in_s: "3600" }, "bad_expiry"],
    ];
    for (const [extra, code] of cases) {
      const v = await validateHello(JSON.stringify(helloFrame(jwt, SID, HASH, 3600, extra)), JWK);
      expect(v, JSON.stringify(extra)).toEqual({ ok: false, code });
    }
    expect(await validateHello("[1,2]", JWK)).toEqual({ ok: false, code: "bad_hello" });
    expect(await validateHello("{", JWK)).toEqual({ ok: false, code: "bad_hello" });
  });
});

describe("frames", () => {
  it("recognises only the lab's tiny end frame", () => {
    expect(isEndFrame('{"t":"end"}')).toBe(true);
    expect(isEndFrame('{ "t" : "end" }')).toBe(true);
    expect(isEndFrame('{"jsonrpc":"2.0","id":1,"result":{"text":"end"}}')).toBe(false);
    expect(isEndFrame('{"t":"end","x":"' + "y".repeat(100) + '"}')).toBe(false); // too long to be ours
    expect(isEndFrame("end")).toBe(false);
  });

  it("measures frames in UTF-8 bytes against the 1 MiB limit", () => {
    expect(frameTooLarge("x".repeat(MAX_FRAME_BYTES))).toBe(false);
    expect(frameTooLarge("x".repeat(MAX_FRAME_BYTES + 1))).toBe(true);
    // 3-byte characters: under the char limit, over the byte limit
    expect(frameTooLarge("€".repeat(Math.floor(MAX_FRAME_BYTES / 3) + 1))).toBe(true);
    expect(frameTooLarge(new ArrayBuffer(MAX_FRAME_BYTES + 1))).toBe(true);
    expect(frameTooLarge(new ArrayBuffer(16))).toBe(false);
  });

  it("only ever closes with a code the runtime accepts", () => {
    expect(safeCloseCode(1000)).toBe(1000);
    expect(safeCloseCode(1009)).toBe(1009);
    expect(safeCloseCode(4404)).toBe(4404);
    expect(safeCloseCode(1005)).toBe(Close.INTERNAL);
    expect(safeCloseCode(1006)).toBe(Close.INTERNAL);
    expect(safeCloseCode(2000)).toBe(Close.INTERNAL);
    expect(safeCloseCode(5000)).toBe(Close.INTERNAL);
    expect(safeCloseCode(0)).toBe(Close.INTERNAL);
    expect(safeCloseCode(1006, Close.GOING_AWAY)).toBe(Close.GOING_AWAY);
  });
});

describe("tokenMatches", () => {
  it("compares the bearer without leaking through length", async () => {
    expect(await tokenMatches("secret-token", "secret-token")).toBe(true);
    expect(await tokenMatches("secret-token!", "secret-token")).toBe(false);
    expect(await tokenMatches("", "secret-token")).toBe(false);
    expect(await tokenMatches(null, "secret-token")).toBe(false);
    expect(await tokenMatches("secret-token", undefined)).toBe(false);
    expect(await tokenMatches("secret-token", "")).toBe(false);
  });
});
