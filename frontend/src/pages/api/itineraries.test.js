import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { createServer } from "vite";

import { JSDOM } from "jsdom";

const frontendRoot = fileURLToPath(new URL("../../..", import.meta.url));
let viteServer;
let itinerariesApi;
let apiClient;
let dom;

before(async () => {
  process.env.VITE_DATA_MODE = "api";
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.localStorage = dom.window.localStorage;

  viteServer = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
  });
  itinerariesApi = await viteServer.ssrLoadModule("/src/api/itineraries.js");
  const clientModule = await viteServer.ssrLoadModule("/src/api/client.js");
  apiClient = clientModule.default;
});

after(async () => {
  if (viteServer) {
    await viteServer.close();
  }
});

test("itineraries API exports required functions", () => {
  assert.equal(typeof itinerariesApi.listActiveLocations, "function");
  assert.equal(typeof itinerariesApi.previewItinerary, "function");
  assert.equal(typeof itinerariesApi.saveItinerary, "function");
  assert.equal(typeof itinerariesApi.listSavedItineraries, "function");
  assert.equal(typeof itinerariesApi.getSavedItinerary, "function");
});

test("previewItinerary dispatches POST /ai/itineraries/preview with criteria", async () => {
  let capturedConfig = null;
  apiClient.defaults.adapter = async (config) => {
    capturedConfig = config;
    return {
      status: 200,
      data: {
        status: "VALIDATED",
        fingerprint: "test-fp-123",
        items: [],
      },
      headers: {},
      config,
    };
  };

  const payload = {
    tripDate: "2026-10-25",
    startTime: "08:00",
    endTime: "17:00",
    startLocationId: "loc-1",
  };

  const result = await itinerariesApi.previewItinerary(payload);
  assert.equal(capturedConfig.method, "post");
  assert.equal(capturedConfig.url, "/ai/itineraries/preview");
  assert.equal(capturedConfig.timeout, 65000, "allow bounded 60s local model inference");
  assert.deepEqual(JSON.parse(capturedConfig.data), payload);
  assert.equal(result.status, "VALIDATED");
  assert.equal(result.fingerprint, "test-fp-123");
});

test("saveItinerary dispatches POST /itineraries with title and fingerprint", async () => {
  let capturedConfig = null;
  apiClient.defaults.adapter = async (config) => {
    capturedConfig = config;
    return {
      status: 201,
      data: {
        id: "trip-999",
        title: "Chuyến đi mẫu",
      },
      headers: {},
      config,
    };
  };

  const savePayload = {
    title: "Chuyến đi mẫu",
    fingerprint: "test-fp-123",
    preview: {
      tripDate: "2026-10-25",
      startTime: "08:00",
      endTime: "17:00",
      startLocationId: "loc-1",
    },
  };

  const saved = await itinerariesApi.saveItinerary(savePayload);
  assert.equal(capturedConfig.method, "post");
  assert.equal(capturedConfig.url, "/itineraries");
  assert.deepEqual(JSON.parse(capturedConfig.data), savePayload);
  assert.equal(saved.id, "trip-999");
});

test("listSavedItineraries dispatches GET /itineraries", async () => {
  let capturedConfig = null;
  apiClient.defaults.adapter = async (config) => {
    capturedConfig = config;
    return {
      status: 200,
      data: [{ id: "trip-1", title: "Trip 1" }],
      headers: {},
      config,
    };
  };

  const list = await itinerariesApi.listSavedItineraries();
  assert.equal(capturedConfig.method, "get");
  assert.equal(capturedConfig.url, "/itineraries");
  assert.equal(list.length, 1);
  assert.equal(list[0].id, "trip-1");
});

test("getSavedItinerary dispatches GET /itineraries/:id", async () => {
  let capturedConfig = null;
  apiClient.defaults.adapter = async (config) => {
    capturedConfig = config;
    return {
      status: 200,
      data: { id: "trip-123", title: "Trip 123 Detail" },
      headers: {},
      config,
    };
  };

  const detail = await itinerariesApi.getSavedItinerary("trip-123");
  assert.equal(capturedConfig.method, "get");
  assert.equal(capturedConfig.url, "/itineraries/trip-123");
  assert.equal(detail.id, "trip-123");
});
