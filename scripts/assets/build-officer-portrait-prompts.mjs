import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const out = resolve(root, 'output/imagegen/officer-portraits-20260927');
const briefs = JSON.parse(readFileSync(resolve(out, 'briefs.json'), 'utf8')).assets;
const game = { window: { Game: {} } };
vm.runInNewContext(readFileSync(resolve(root, 'frontend/js/data.js'), 'utf8'), game);
const pool = Array.from(game.window.Game.DATA.officerNames);

if (briefs.length !== pool.length || briefs.some((entry, i) => entry.name !== pool[i] || entry.id !== `officer-${String(i + 1).padStart(2, '0')}`)) {
  throw new Error('Portrait briefs no longer match the academy name pool. Review the mapping before generating.');
}

const common = `Use case: stylized-concept
Asset type: square 1940s strategy-game officer detail portrait, one asset in a coordinated character set.
Primary request: ONE wholly fictional military character represented as a premium hand-painted 3D game miniature bust. This is an original invented person, not a depiction, likeness, recreation, or face study of any real officer. Do not use historical photos as a reference.
Style/medium: high-quality 3D game character render with tactile sculpted surfaces and matte painted materials. Adult human proportions, individualized facial structure, legible expression, restrained stylization; unmistakably game CG rather than live-action photography, while avoiding cartoon exaggeration.
Composition/framing: square 1024x1024; face and upper chest centered in a consistent three-quarter bust crop, with entire cap, shoulders, and collar inside frame. Head large enough to read clearly at 112px. No hands, weapons, pedestal, scenery, or extra characters.
Scene/backdrop: dark olive-charcoal studio backdrop with a very subtle dark hexagonal texture, matching the existing rank-avatar family. The backdrop should stay behind the head rather than becoming a UI frame.
Lighting/mood: soft upper-left key light, restrained fill, sharp clear face, dignified military bearing, varied expression and bearing according to the individual brief. No shadow obscuring the eyes.
Materials/textures: period-inspired cotton twill, wool, worn leather, simple metal fastenings. Period-inspired rather than documentary uniform reconstruction. No exact decorations or historically distinctive personal accessories.
Constraints: All faces in this set must look different. Follow this asset's distinctive facial silhouette, age, headgear, collar, pose, and accent colors. No resemblance to a named or recognizable real person. No readable text, numbers, names, flags, national emblems, political symbols, swastikas, SS runes, medals, insignia, logos, or watermark.
Avoid: real-person portrait, photo realism, celebrity or historical likeness, propaganda, anime, chibi, caricature, identical generic faces, giant eyes or head, low-poly toy, modern camouflage, tactical headset, multiple people, contact sheet, illustrated border.
`;

const jobs = briefs.map(entry => {
  const prompt = `${common}Role reference: ${entry.theater}.\nDistinctive fictional character design: ${entry.visual}\n`;
  writeFileSync(resolve(out, `${entry.id}.prompt.txt`), prompt);
  return { prompt, out: `${entry.id}.png`, model: 'gpt-image-2', size: '1024x1024', quality: 'high' };
});
writeFileSync(resolve(out, 'batch.jsonl'), jobs.map(job => JSON.stringify(job)).join('\n') + '\n');
console.log(`Prepared ${jobs.length} distinct fictional officer prompts; no image API call made.`);
