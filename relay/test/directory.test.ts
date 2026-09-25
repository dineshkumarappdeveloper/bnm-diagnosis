import { afterEach, describe, expect, it } from "vitest";
import { JOIN_LIMIT } from "../src/directory";
import { cleanup, directoryStub } from "./helpers";

afterEach(cleanup);

const A = "a".repeat(64);
const B = "b".repeat(64);
const S1 = "11111111-1111-4111-8111-111111111111";
const S2 = "22222222-2222-4222-8222-222222222222";

describe("SessionDirectory", () => {
  it("maps a code hash to its session until it expires", async () => {
    const dir = directoryStub();
    const soon = Date.now() + 500;
    expect(await dir.register(A, S1, soon)).toBe("ok");
    expect(await dir.lookup(A)).toEqual({ sessionId: S1, expiresAt: soon });
    await new Promise((r) => setTimeout(r, 600));
    expect(await dir.lookup(A)).toBeNull();
  });

  it("keeps a live code for its own session and refuses it to another", async () => {
    const dir = directoryStub();
    const later = Date.now() + 60_000;
    expect(await dir.register(A, S1, later)).toBe("ok");
    expect(await dir.register(A, S1, later)).toBe("ok"); // reconnect
    expect(await dir.register(A, S2, later)).toBe("code_in_use");
    expect(await dir.lookup(A)).toEqual({ sessionId: S1, expiresAt: later });
  });

  it("lets a session hold only its latest code", async () => {
    const dir = directoryStub();
    const later = Date.now() + 60_000;
    await dir.register(A, S1, later);
    await dir.register(B, S1, later);
    expect(await dir.lookup(A)).toBeNull();
    expect(await dir.lookup(B)).toEqual({ sessionId: S1, expiresAt: later });
  });

  it("unregisters only when the code still belongs to that session", async () => {
    const dir = directoryStub();
    const later = Date.now() + 60_000;
    await dir.register(A, S1, later);
    await dir.unregister(A, S2);
    expect(await dir.lookup(A)).not.toBeNull();
    await dir.unregister(A, S1);
    expect(await dir.lookup(A)).toBeNull();
  });

  it(`allows ${JOIN_LIMIT} joins a minute per key, then refuses`, async () => {
    const dir = directoryStub();
    for (let i = 0; i < JOIN_LIMIT; i++) expect(await dir.allowJoin("tok")).toBe(true);
    expect(await dir.allowJoin("tok")).toBe(false);
    expect(await dir.allowJoin("other")).toBe(true); // budgets are per key
  });
});
