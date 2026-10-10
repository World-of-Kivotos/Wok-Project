// 给截图用：找一局「完美机器人 + 必有瓶 + 服务端判金瓶」能打出闪耀的输入，写成 plan 片段。
//   node tools/find-radiant.mjs [station=0] [slotStar=1] [level=10] [motion=2]
// 页面里用 __zs.select({station, slotStar, level, motion, seed, goldPm: 1000, gold: 1}) + __zs.feed(inputs) 逐 tick 重放。
import * as Z from '../sim/sim.js';
import { playGame } from '../sim/bots.mjs';
const [station = 0, slotStar = 1, level = 10, motion = 2] = process.argv.slice(2).map(Number);
for (let seed = 1; seed < 500; seed++) {
  const cfg = { station, star: slotStar, chefLevel: level, motion, mastery: 0, seed, goldPm: 1000 };
  const { state, inputs } = playGame(cfg, 'perfect');
  const r = Z.result(state, { gold: 1 });
  if (r.quality === Z.QUALITY.RADIANT) {
    console.log(JSON.stringify({ seed, ticks: inputs.length, score: r.score, inputs }));
    process.exit(0);
  }
}
console.error('没找到');
process.exit(1);
