#!/usr/bin/env node
/**
 * Copies the address-name scheme from the sensors.social map into www/names.js.
 * The map is the single source (src/utils/addressNames.js): word lists, their order and the salt.
 * The app has no bundler and no @polkadot libraries, so the generated file carries its own
 * base58 (SS58) decoder and BLAKE2b-256. Names must match the map exactly — re-run this after
 * any change on the map, and check: node tools/sync-names.mjs --check <vectors.json>
 *
 *   node tools/sync-names.mjs [path/to/sensors.social]
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, "../www/names.js");

if (process.argv[2] === "--check") {
  const ctx = { TextEncoder };
  ctx.window = ctx;
  vm.runInNewContext(readFileSync(OUT, "utf8"), ctx);
  const vec = JSON.parse(readFileSync(process.argv[3], "utf8"));
  const bad = vec.filter((v) => ctx.sensorName(v.id) !== v.sensor || ctx.ownerName(v.id) !== v.owner);
  console.log(`${vec.length} checked, ${bad.length} mismatches`);
  process.exit(bad.length ? 1 : 0);
}

const SITE = resolve(process.argv[2] || join(HERE, "../../sensors.social"));
const src = readFileSync(join(SITE, "src/utils/addressNames.js"), "utf8");
const salt = src.match(/new TextEncoder\(\)\.encode\("([^"]+)"\)/)[1];
const list = (name) => JSON.parse(src.match(new RegExp(`const ${name} = (\\[[\\s\\S]*?\\]);`))[1].replace(/,\s*\]$/, "]"));
const words = { ADJECTIVES: list("ADJECTIVES"), PLANTS: list("PLANTS"), ANIMALS: list("ANIMALS") };

const body = String.raw`
(function () {
  var SALT = new TextEncoder().encode(${JSON.stringify(salt)});
  var ADJECTIVES = ${JSON.stringify(words.ADJECTIVES)};
  var PLANTS = ${JSON.stringify(words.PLANTS)};
  var ANIMALS = ${JSON.stringify(words.ANIMALS)};

  var B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  function base58(s) {
    var bytes = [0];
    for (var i = 0; i < s.length; i++) {
      var c = B58.indexOf(s[i]);
      if (c < 0) throw new Error("not base58");
      for (var j = 0; j < bytes.length; j++) { c += bytes[j] * 58; bytes[j] = c & 255; c >>= 8; }
      while (c) { bytes.push(c & 255); c >>= 8; }
    }
    for (var k = 0; k < s.length && s[k] === "1"; k++) bytes.push(0);
    return Uint8Array.from(bytes.reverse());
  }
  /** 32-byte public key: SS58 (1- or 2-byte prefix) or the 64-hex id of a DIY sensor. */
  function keyBytes(id) {
    var h = id.replace(/^0x/, "");
    if (/^[0-9a-fA-F]{64}$/.test(h)) return Uint8Array.from(h.match(/../g), function (b) { return parseInt(b, 16); });
    var raw = base58(id);
    var p = raw[0] & 0x40 ? 2 : 1;
    if (raw.length !== p + 34) throw new Error("not ss58");
    return raw.slice(p, p + 32);
  }

  // BLAKE2b (RFC 7693), 64-bit words as pairs of uint32 (lo, hi)
  var IV = [0xf3bcc908, 0x6a09e667, 0x84caa73b, 0xbb67ae85, 0xfe94f82b, 0x3c6ef372, 0x5f1d36f1, 0xa54ff53a,
    0xade682d1, 0x510e527f, 0x2b3e6c1f, 0x9b05688c, 0xfb41bd6b, 0x1f83d9ab, 0x137e2179, 0x5be0cd19];
  var SIGMA = [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15, 14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3,
    11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4, 7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8,
    9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13, 2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9,
    12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11, 13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10,
    6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5, 10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0,
    0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15, 14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3];
  var v = new Uint32Array(32), m = new Uint32Array(32);
  function add(a, b) { // v[a] += v[b]
    var lo = v[a] + v[b], hi = v[a + 1] + v[b + 1];
    if (lo >= 0x100000000) hi++;
    v[a] = lo; v[a + 1] = hi;
  }
  function addM(a, lo0, hi0) {
    var lo = v[a] + lo0, hi = v[a + 1] + hi0;
    if (lo >= 0x100000000) hi++;
    v[a] = lo; v[a + 1] = hi;
  }
  function xorRot(a, b, r) { // v[a] = rotr64(v[a] ^ v[b], r)
    var lo = v[a] ^ v[b], hi = v[a + 1] ^ v[b + 1];
    if (r === 32) { v[a] = hi; v[a + 1] = lo; return; }
    if (r === 63) { v[a] = (hi >>> 31) ^ (lo << 1); v[a + 1] = (lo >>> 31) ^ (hi << 1); return; }
    if (r < 32) { v[a] = (lo >>> r) ^ (hi << (32 - r)); v[a + 1] = (hi >>> r) ^ (lo << (32 - r)); return; }
    r -= 32; v[a] = (hi >>> r) ^ (lo << (32 - r)); v[a + 1] = (lo >>> r) ^ (hi << (32 - r));
  }
  function G(a, b, c, d, x, y) {
    add(a, b); addM(a, m[x], m[x + 1]); xorRot(d, a, 32);
    add(c, d); xorRot(b, c, 24);
    add(a, b); addM(a, m[y], m[y + 1]); xorRot(d, a, 16);
    add(c, d); xorRot(b, c, 63);
  }
  function compress(h, block, t, last) {
    for (var i = 0; i < 16; i++) { v[i] = h[i]; v[i + 16] = IV[i]; }
    v[24] ^= t; v[25] ^= t / 0x100000000;
    if (last) { v[28] = ~v[28]; v[29] = ~v[29]; }
    for (i = 0; i < 32; i++) m[i] = block[i * 4] ^ (block[i * 4 + 1] << 8) ^ (block[i * 4 + 2] << 16) ^ (block[i * 4 + 3] << 24);
    for (i = 0; i < 12; i++) {
      var s = SIGMA.slice(i * 16, i * 16 + 16);
      G(0, 8, 16, 24, s[0] * 2, s[1] * 2); G(2, 10, 18, 26, s[2] * 2, s[3] * 2);
      G(4, 12, 20, 28, s[4] * 2, s[5] * 2); G(6, 14, 22, 30, s[6] * 2, s[7] * 2);
      G(0, 10, 20, 30, s[8] * 2, s[9] * 2); G(2, 12, 22, 24, s[10] * 2, s[11] * 2);
      G(4, 14, 16, 26, s[12] * 2, s[13] * 2); G(6, 8, 18, 28, s[14] * 2, s[15] * 2);
    }
    for (i = 0; i < 16; i++) h[i] = h[i] ^ v[i] ^ v[i + 16];
  }
  function blake2b256(input) {
    var h = new Uint32Array(IV);
    h[0] ^= 0x01010000 ^ 32; // digest 32 bytes, no key, fanout 1, depth 1
    var t = 0, block = new Uint8Array(128), n = 0;
    for (var i = 0; i < input.length; i++) {
      if (n === 128) { t += 128; compress(h, block, t, false); n = 0; }
      block[n++] = input[i];
    }
    t += n;
    while (n < 128) block[n++] = 0;
    compress(h, block, t, true);
    var out = new Uint8Array(32);
    for (i = 0; i < 32; i++) out[i] = h[i >> 2] >>> (8 * (i & 3));
    return out;
  }

  function readMod(b, o, mod) {
    var hi = 0, lo = 0;
    for (var i = 0; i < 4; i++) hi = hi * 256 + b[o + i];
    for (i = 4; i < 8; i++) lo = lo * 256 + b[o + i];
    return ((hi % mod) * (Math.pow(2, 32) % mod) + (lo % mod)) % mod;
  }
  var cap = function (w) { return w.charAt(0).toUpperCase() + w.slice(1); };
  var cache = {};
  function nameFrom(id, nouns, kind) {
    id = String(id || "").trim();
    if (!id) return "";
    var key = kind + ":" + id;
    if (cache[key]) return cache[key];
    var name;
    try {
      var pub = keyBytes(id), input = new Uint8Array(pub.length + SALT.length);
      input.set(pub); input.set(SALT, pub.length);
      var h = blake2b256(input);
      name = cap(ADJECTIVES[readMod(h, 0, ADJECTIVES.length)]) + " " + cap(nouns[readMod(h, 8, nouns.length)]);
    } catch (e) {
      name = id.length > 12 ? id.slice(0, 4) + "…" + id.slice(-4) : id;
    }
    return (cache[key] = name);
  }
  /** Sensors are plants ("Sleepy Clematis"), owners are animals ("Woolly Pony") — as on the map. */
  window.sensorName = function (id) { return nameFrom(id, PLANTS, "sensor"); };
  window.ownerName = function (id) { return nameFrom(id, ANIMALS, "owner"); };
})();
`;

const commit = (() => { try { return readFileSync(join(SITE, ".git/HEAD"), "utf8").trim(); } catch { return ""; } })();
writeFileSync(OUT, `/* Generated by tools/sync-names.mjs from the sensors.social map (src/utils/addressNames.js${commit ? ", " + commit : ""}). Do not edit by hand. */\n` + body.trimStart());
console.log(`www/names.js: ${words.ADJECTIVES.length} adjectives, ${words.PLANTS.length} plants, ${words.ANIMALS.length} animals`);
