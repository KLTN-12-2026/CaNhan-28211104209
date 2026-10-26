import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { URL } from "node:url";
import { test } from "node:test";

const css = readFileSync(new URL("../index.css", import.meta.url), "utf8");
function token(name) { const match = css.match(new RegExp(`${name}:\\s*(#[0-9a-f]{6})`, "i")); assert.ok(match, name); return match[1]; }
function luminance(hex) {
  const [r,g,b] = hex.slice(1).match(/../g).map(value => { const n=parseInt(value,16)/255; return n<=0.04045?n/12.92:((n+0.055)/1.055)**2.4; });
  return 0.2126*r+0.7152*g+0.0722*b;
}
function contrast(a,b) { const values=[luminance(a),luminance(b)].sort((x,y)=>y-x);return (values[0]+0.05)/(values[1]+0.05); }
test("secondary text remains readable on white cards and warm page background", () => {
  for(const background of [token("--color-surface"),token("--color-page-background")]) assert.ok(contrast(token("--color-readable-muted"),background)>=4.5);
  assert.ok(contrast("#94a3b8","#ffffff")<4.5,"old slate400 fails normal text contrast");
});
test("control borders and primary labels retain contrast on adjacent backgrounds", () => {
  for(const background of [token("--color-surface"),token("--color-page-background")]) assert.ok(contrast(token("--color-secondary-border"),background)>=3);
  for(const background of [token("--color-action-primary"),token("--color-action-primary-hover")]) assert.ok(contrast("#1a1a1a",background)>=4.5);
});
