import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { URL } from "node:url";
import fs from "node:fs";
import { test } from "node:test";

const media = new URL("../../public/media/vehicles/", import.meta.url);
const registry = JSON.parse(fs.readFileSync(new URL("expansion-licenses.json", media), "utf8"));

test("Expanded catalog has seventeen distinct decoded JPEG assets matching recorded hashes", () => {
  assert.equal(registry.length, 17);
  assert.equal(new Set(registry.map(p => p.model)).size, 17);
  assert.equal(new Set(registry.map(p => p.sha256)).size, 17);
  for (const p of registry) {
    assert.match(p.file, /^[a-z0-9-]+\.jpg$/);
    assert.equal(p.url, "/media/vehicles/" + p.file);
    const data = fs.readFileSync(new URL(p.file, media));
    assert.equal(data.subarray(0, 2).toString("hex"), "ffd8");
    assert.equal(data.length, p.bytes);
    assert.equal(createHash("sha256").update(data).digest("hex"), p.sha256);
  }
});

test("Each expanded image retains source, license, credit and illustrative disclosure", () => {
  const credits = fs.readFileSync(new URL("expansion-licenses.html", media), "utf8");
  for (const p of registry) {
    assert.equal(new URL(p.sourceUrl).hostname, "commons.wikimedia.org");
    assert.match(p.licenseUrl, /^https:\/\/creativecommons\.org\/licenses\/by(?:-sa)?\/[234]\.0\/?$/);
    assert.ok(p.author.trim());
    assert.ok(p.attribution.includes(p.author));
    assert.ok(p.attribution.includes(p.sourceUrl));
    assert.ok(p.attribution.includes(p.licenseUrl));
    assert.ok(p.attribution.length <= 1000);
    assert.match(p.changes, /not the actual rental unit/);
    assert.ok(credits.includes(p.sha256));
    assert.ok(credits.includes(p.url));
  }
  assert.ok(fs.readFileSync(new URL("licenses.html", media), "utf8").includes("/media/vehicles/expansion-licenses.html"));
});
