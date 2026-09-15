#!/usr/bin/env node
/**
 * Renders tools/screenshots/screens.html to docs/screenshots/*.png
 * for the README. Layout follows the Compose screens in app/src/main/java/com/thunderplay/ui.
 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../..");
const html = path.join(here, "screens.html");
const outDir = path.join(repo, "docs/screenshots");

const vendor = "/tmp/thunderplay-screenshots-node";
if (!fs.existsSync(path.join(vendor, "node_modules/puppeteer-core"))) {
  fs.mkdirSync(vendor, { recursive: true });
  const init = spawnSync("npm", ["init", "-y"], { cwd: vendor, stdio: "inherit" });
  if (init.status !== 0) process.exit(init.status ?? 1);
  const install = spawnSync("npm", ["install", "puppeteer-core@24"], {
    cwd: vendor,
    stdio: "inherit",
  });
  if (install.status !== 0) process.exit(install.status ?? 1);
}

const require = createRequire(path.join(vendor, "package.json"));
const puppeteer = require("puppeteer-core");

const screens = [
  "library",
  "now-playing",
  "playlists",
  "history",
  "prompts",
  "ab-test",
  "settings",
  "web-player",
];

const WIDTH = 412;
const HEIGHT = 892;

fs.mkdirSync(outDir, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: "/usr/bin/google-chrome-stable",
  headless: true,
  args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--font-render-hinting=none"],
});

try {
  for (const id of screens) {
    const page = await browser.newPage();
    await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 2 });
    const url = `${pathToFileURL(html).href}?screen=${id}`;
    await page.goto(url, { waitUntil: "networkidle0", timeout: 60_000 });
    await page.evaluate(() => document.fonts.ready);
    await new Promise((r) => setTimeout(r, 400));
    const dest = path.join(outDir, `${id}.png`);
    await page.screenshot({ path: dest, type: "png" });
    await page.close();
    console.log("wrote", path.relative(repo, dest));
  }
} finally {
  await browser.close();
}
