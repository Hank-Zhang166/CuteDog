import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const testPose = process.argv.includes('--pose-test');
const watch = process.argv.includes('--wait');
const client = new Client({ name: 'cutedog-local-check', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: process.execPath, args: [fileURLToPath(new URL('./server.mjs', import.meta.url))], env: process.env,
});
await client.connect(transport);
async function call(name, args = {}) {
  const answer = await client.callTool({ name, arguments: args });
  if (answer.isError) throw new Error(answer.content[0].text);
  return JSON.parse(answer.content[0].text);
}

try {
  const tools = (await client.listTools()).tools.map(t => t.name);
  console.log(JSON.stringify({ tools }));
  let status, last;
  const deadline = Date.now() + (watch ? 600000 : 1);
  do {
    try { status = await call('cubism_connect'); }
    catch (error) { status = { connected: false, error: error.message }; }
    if (JSON.stringify(status) !== last) console.log(JSON.stringify(status));
    last = JSON.stringify(status);
    if (status.approved || !watch) break;
    await delay(1500);
  } while (Date.now() < deadline);
  if (!status.approved) throw new Error('Editor connection or approval pending');
  const docs = await call('cubism_documents');
  console.log(JSON.stringify(docs));
  if (process.argv.includes('--display')) {
    const log = await call('cubism_display_message', { message: 'CuteDog MCP connected to Live2D Cubism Editor.' });
    console.log(JSON.stringify(log));
  }
  if (testPose) {
    const { Parameters, ModelUID } = await call('cubism_parameters');
    const eligible = Parameters.filter(p => Number(p.Max) > Number(p.Min));
    const def = eligible.find(p => p.Keyform?.length > 1) || eligible[0];
    if (!def) throw new Error('The current document has no adjustable parameter yet');
    const args = { modelUid: ModelUID, ids: [def.Id] };
    const value = async () => Number((await call('cubism_get_values', args)).Parameters[0].Value);
    const set = v => call('cubism_set_values', { modelUid: ModelUID, parameters: [{ id: def.Id, value: v }] });
    const before = await value();
    const range = def.Max - def.Min;
    const target = Math.abs(def.Max - before) > .5 * range ? before + .2 * range : before - .2 * range;
    let after;
    try {
      await set(target);
      await delay(65);
      after = await value();
    } finally {
      await set(before);
    }
    const restored = await value();
    await call('cubism_clear_values', { modelUid: ModelUID });
    console.log(JSON.stringify({ ModelUID, parameterCount: Parameters.length, parameterId: def.Id, before, target, after, restored }));
    if (Math.abs(after - target) > range * .001 || Math.abs(restored - before) > range * .001) {
      throw new Error('Parameter readback differed from the commanded or restored value');
    }
  }
} finally {
  await client.close();
}
