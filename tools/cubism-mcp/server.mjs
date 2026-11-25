import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { setTimeout as delay } from 'node:timers/promises';
import { Editor, endpoint } from './editor.mjs';

const server = new McpServer({ name: 'cutedog-cubism', version: '1.0.0' });
const editor = new Editor();
const modelUid = z.string().min(1).optional().describe('Omit to use the current model. Cubism UIDs are session-specific.');
const parameter = z.object({ id: z.string().min(1), value: z.number().finite() });
let previous = Promise.resolve();

function tool(name, description, inputSchema, readOnly, run) {
  server.registerTool(name, {
    description, inputSchema, annotations: { readOnlyHint: readOnly, destructiveHint: false, openWorldHint: false },
  }, args => {
    const result = previous.then(async () => {
      try {
        const value = await run(args);
        return { content: [{ type: 'text', text: JSON.stringify(value) }] };
      } catch (error) {
        return { isError: true, content: [{ type: 'text', text: error.message }] };
      }
    });
    previous = result.catch(() => {});
    return result;
  });
}

tool('cubism_connect', 'Connect to the local Cubism Editor and report its approval state.', {}, false,
  () => editor.status());

tool('cubism_documents', 'Read the current edit mode and all open model, animation and physics documents.', {}, true, async () => {
  await editor.requireApproval();
  const documents = await editor.request('GetDocuments');
  return { endpoint, ...await editor.request('GetCurrentEditMode'), ...documents };
});

tool('cubism_parameters', 'Inspect model parameter IDs, names, ranges, groups and registered keyform values available from this editor.', { modelUid }, true, async args => {
  await editor.requireApproval();
  const ModelUID = await editor.model(args.modelUid);
  const parameters = await editor.request('GetParameters', { ModelUID });
  return { ModelUID, ...parameters, ...await editor.request('GetParameterGroups', { ModelUID }) };
});

tool('cubism_get_values', 'Read model parameter values from the editor.', {
  modelUid, ids: z.array(z.string().min(1)).min(1).optional(),
}, true, async args => {
  await editor.requireApproval();
  const ModelUID = await editor.model(args.modelUid);
  return { ModelUID, ...await editor.request('GetParameterValues', { ModelUID, ...(args.ids ? { Ids: args.ids } : {}) }) };
});

tool('cubism_set_values', 'Pose the active editor model using its existing parameters. Values are validated using this model’s actual ranges.', {
  modelUid, parameters: z.array(parameter).min(1),
}, false, async args => {
  await editor.requireApproval();
  const ModelUID = await editor.model(args.modelUid);
  const schema = await editor.request('GetParameters', { ModelUID });
  const definitions = new Map(schema.Parameters.map(p => [p.Id, p]));
  const seen = new Set();
  const Parameters = args.parameters.map(({ id, value }) => {
    const def = definitions.get(id);
    if (!def) throw new Error('Unknown parameter: ' + id);
    if (seen.has(id)) throw new Error('Duplicate parameter: ' + id);
    if (value < def.Min || value > def.Max) throw new Error(id + ' must be between ' + def.Min + ' and ' + def.Max);
    seen.add(id);
    return { Id: id, Value: value };
  });
  await editor.request('SetParameterValues', { ModelUID, Parameters });
  return { ModelUID, accepted: Parameters };
});

tool('cubism_clear_values', 'Clear externally supplied parameter overrides for this model.', { modelUid }, false, async args => {
  await editor.requireApproval();
  const ModelUID = await editor.model(args.modelUid);
  await editor.request('ClearParameterValues', { ModelUID });
  return { ModelUID, cleared: true };
});

tool('cubism_preview_motion', 'Play a sampled parameter sequence in the live editor, then restore the original pose. Supply one value per ID in every frame.', {
  modelUid, ids: z.array(z.string().min(1)).min(1),
  fps: z.number().int().min(12).max(60).default(24),
  frames: z.array(z.array(z.number().finite())).min(2).max(600),
}, false, async args => {
  await editor.requireApproval();
  const ModelUID = await editor.model(args.modelUid);
  const metadata = await editor.request('GetParameters', { ModelUID });
  const byId = new Map(metadata.Parameters.map(p => [p.Id, p]));
  if (new Set(args.ids).size !== args.ids.length) throw new Error('Duplicate parameter IDs');
  const defs = args.ids.map(id => {
    if (!byId.has(id)) throw new Error('Unknown parameter: ' + id);
    return byId.get(id);
  });
  for (const frame of args.frames) {
    if (frame.length !== defs.length) throw new Error('Frame does not have one value per parameter');
    for (let i = 0; i < defs.length; i++) {
      if (frame[i] < defs[i].Min || frame[i] > defs[i].Max) throw new Error('Value out of range: ' + defs[i].Id);
    }
  }
  const initial = await editor.request('GetParameterValues', { ModelUID, Ids: args.ids });
  const start = performance.now();
  const interval = 1000 / args.fps;
  let shown = 0;
  try {
    for (let i = 0; i < args.frames.length; i++) {
      await delay(Math.max(0, start + i * interval - performance.now()));
      const Parameters = args.ids.map((Id, j) => ({ Id, Value: args.frames[i][j] }));
      await editor.request('SetParameterValues', { ModelUID, Parameters });
      shown++;
    }
    await delay(Math.max(0, start + args.frames.length * interval - performance.now()));
  } finally {
    await editor.request('SetParameterValues', { ModelUID, Parameters: initial.Parameters });
    await editor.request('ClearParameterValues', { ModelUID });
  }
  return { ModelUID, shown, requestedFps: args.fps, elapsedMs: Math.round(performance.now() - start) };
});

tool('cubism_display_message', 'Send a message to the visible Cubism Editor log palette.', {
  message: z.string().min(1).max(5000), level: z.enum(['info', 'warning']).default('info'),
}, false, async args => {
  await editor.requireApproval();
  await editor.request('SendCubismLog', { Message: args.message, Type: args.level, Display: true });
  return { displayed: true };
});

process.stdin.on('end', () => editor.close());
await server.connect(new StdioServerTransport());
