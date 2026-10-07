const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const frames = path.join(root, 'img/map/wild-oil-frames');

test('地图油田动画沿用石油基地素材并输出完整的抽油机摆动帧', () => {
  const generator = fs.readFileSync(path.join(root, '../scripts/assets/prepare-map-oilfield.py'), 'utf8');
  const worldMap = fs.readFileSync(path.join(root, 'js/world-map.js'), 'utf8');
  assert.match(generator, /frontend\/img\/buildings\/garden\/oilfield\.webp/);
  assert.match(generator, /math\.sin\(phase\) \* 6\.0/);
  assert.ok(worldMap.includes("'-frames/frame_' + frame + '.png?v=5.11-pumpjack-animation"));

  const firstFrame = fs.readFileSync(path.join(frames, 'frame_00.png'));
  assert.equal(firstFrame.toString('hex', 0, 8), '89504e470d0a1a0a');
  assert.equal(firstFrame.readUInt32BE(16), 384);
  assert.equal(firstFrame.readUInt32BE(20), 384);
  for (let index = 0; index < 12; index++) {
    const frame = fs.readFileSync(path.join(frames, `frame_${String(index).padStart(2, '0')}.png`));
    assert.equal(frame.toString('hex', 0, 8), '89504e470d0a1a0a');
    assert.equal(frame.readUInt32BE(16), 384);
    assert.equal(frame.readUInt32BE(20), 384);
  }
  assert.notDeepEqual(firstFrame, fs.readFileSync(path.join(frames, 'frame_03.png')));
  assert.notDeepEqual(firstFrame, fs.readFileSync(path.join(frames, 'frame_09.png')));
});
