import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const assetRoot = path.join(root, 'frontend', 'img');
const outputRoot = path.join(root, 'output');
const jsonPath = path.join(outputRoot, 'asset-provenance.json');
const imageExtensions = new Set(['.svg', '.png', '.webp', '.jpg', '.jpeg', '.gif', '.avif', '.ico']);
const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
});

function localDate(date) {
  return dateFormatter.format(date);
}

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}

function sourceRecord(relativePath) {
  if (relativePath.startsWith('cities/')) return 'output/imagegen/city-models/README.md';
  if (relativePath.startsWith('buildings/garden/')) return 'output/imagegen/realistic-buildings-20260917/README.md';
  if (relativePath.startsWith('map/snow-') || relativePath.startsWith('map/wild-rock-')) {
    return 'output/imagegen/snow-depth/README.md';
  }
  if (/^map\/(npc-fortress|wild-(forest|hill|swamp|grainfield|ironworks|oil|rarefactory))/.test(relativePath)) {
    return 'output/imagegen/map-icons/README.md';
  }
  if (relativePath.startsWith('units/models/')) return 'output/imagegen/unit-models-20260921/README.md';
  if (relativePath.startsWith('units/options/')) return 'frontend/img/units/options/README.md';
  if (relativePath.startsWith('resources/models/')) return 'output/imagegen/resource-models-20260923/batch.jsonl';
  if (relativePath.startsWith('avatars/historical/')) {
    return 'output/imagegen/historical-rank-avatars-20260926/README.md';
  }
  if (relativePath.startsWith('login-battlefield-diorama')) {
    return 'output/imagegen/login-battlefield-diorama-20260927.design.md';
  }
  return null;
}

const previous = fs.existsSync(jsonPath) ? JSON.parse(fs.readFileSync(jsonPath, 'utf8')) : null;
const previousByPath = new Map((previous?.assets || []).map((asset) => [asset.path, asset]));
const files = walk(assetRoot)
  .filter((file) => imageExtensions.has(path.extname(file).toLowerCase()))
  .sort();

const assets = files.map((file) => {
  const relativePath = path.relative(assetRoot, file).split(path.sep).join('/');
  const assetPath = `frontend/img/${relativePath}`;
  const sha256 = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const old = previousByPath.get(assetPath);
  // 已登记且内容未变的文件保留原始 mtime；克隆仓库后的检出时间不应覆盖历史日期。
  const modifiedAt = old?.sha256 === sha256 && old.modifiedAt
    ? old.modifiedAt : fs.statSync(file).mtime.toISOString();
  return {
    path: assetPath,
    format: path.extname(file).slice(1).toLowerCase(),
    author: '汪渊',
    creationDate: localDate(new Date(modifiedAt)),
    dateBasis: '文件最后修改时间，按北京时间换算；作为创作日期参考',
    modifiedAt,
    sha256,
    generationMethod: 'AI 生成',
    sourceRecord: sourceRecord(relativePath),
  };
});

const document = {
  title: '山河远征录游戏图片资源作者与日期登记',
  version: 1,
  recordedOn: '2026-09-29',
  author: '汪渊',
  generationMethod: 'AI 生成',
  scope: 'frontend/img 下当前随游戏交付的图片资源；不含 output 中的候选、原稿和预览图',
  datePolicy: 'creationDate 为资源文件最后修改时间换算的北京时间日期，仅作为创作日期参考，不代表可验证的首次生成时间。modifiedAt 保留原始时间戳；已登记且 SHA-256 未变的资源在重新生成清单时保留原日期。',
  statementBasis: '作者和 AI 生成方式依据项目所有者说明；来源记录仅是找到的相关项目文档，空值表示尚无逐文件的独立生成记录。',
  assetCount: assets.length,
  assets,
};

fs.writeFileSync(jsonPath, `${JSON.stringify(document, null, 2)}\n`, 'utf8');

const groups = new Map();
for (const asset of assets) {
  const list = groups.get(asset.creationDate) || [];
  list.push(asset);
  groups.set(asset.creationDate, list);
}

const lines = [
  '# 游戏图片资源作者与日期登记',
  '',
  `登记日期：${document.recordedOn}`,
  `作者：${document.author}`,
  `生成方式：${document.generationMethod}`,
  `登记范围：${document.scope}`,
  `资源总数：${document.assetCount}`,
  '',
  '## 日期与声明依据',
  '',
  document.datePolicy,
  '',
  document.statementBasis,
  '',
  '本清单是项目素材登记，不替代 AI 服务协议、输入素材授权或发布前的法律审核。',
  '',
  '## 按日期汇总',
  '',
  '| 日期 | 资源数量 | 主要资源目录 |',
  '| --- | ---: | --- |',
];

for (const [date, list] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  const directories = [...new Set(list.map((asset) => path.posix.dirname(asset.path)))];
  lines.push(`| ${date} | ${list.length} | ${directories.map((directory) => `\`${directory}/\``).join('、')} |`);
}

lines.push('', '## 逐文件清单', '', '完整机器可读清单及原始修改时间、SHA-256 见 [`asset-provenance.json`](asset-provenance.json)。', '', '| 文件 | 格式 | 作者 | 创作日期参考 | 生成方式 | 相关记录 |', '| --- | --- | --- | --- | --- | --- |');
for (const asset of assets) {
  const source = asset.sourceRecord ? `\`${asset.sourceRecord}\`` : '暂无逐文件记录';
  lines.push(`| \`${asset.path}\` | ${asset.format} | ${asset.author} | ${asset.creationDate} | ${asset.generationMethod} | ${source} |`);
}

fs.writeFileSync(path.join(outputRoot, 'ASSET_PROVENANCE.md'), `${lines.join('\n')}\n`, 'utf8');
console.log(`Wrote ${assets.length} asset records`);
