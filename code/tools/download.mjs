import fs from 'node:fs/promises';
import path from 'node:path';
const [url, destination] = process.argv.slice(2);
if (!url?.startsWith('https://') || !destination) throw Error('Usage: download.mjs https://URL destination');
const response = await fetch(url, {signal: AbortSignal.timeout(180000)});
if (!response.ok) throw Error(`Download failed: ${response.status}`);
await fs.mkdir(path.dirname(destination), {recursive: true});
await fs.writeFile(destination, new Uint8Array(await response.arrayBuffer()));
console.log(`Downloaded ${path.basename(destination)}`);
