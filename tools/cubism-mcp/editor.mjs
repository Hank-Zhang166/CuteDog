import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const pluginName = 'CuteDog Cubism MCP';
const port = Number(process.env.CUBISM_PORT || 22033);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('CUBISM_PORT must be a valid TCP port');
export const endpoint = 'ws://127.0.0.1:' + port;
const config = process.env.LOCALAPPDATA || process.env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config');
const tokenPath = path.join(config, 'CuteDogCubismMCP', 'session-' + port + '.json');

export class Editor {
  socket = null;
  pending = new Map();

  close() {
    this.socket?.close();
    this.socket = null;
  }

  async connect() {
    if (this.socket?.readyState === WebSocket.OPEN) return;
    const socket = new WebSocket(endpoint);
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Cubism did not accept a connection at ' + endpoint)), 3500);
        socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
        socket.addEventListener('error', () => {
          clearTimeout(timer);
          reject(new Error('Cubism is unavailable at ' + endpoint + '. Enable File > External Application Integration Settings in the editor.'));
        }, { once: true });
      });
      this.socket = socket;
      socket.addEventListener('message', event => {
        let row;
        try { row = JSON.parse(event.data); } catch { return; }
        const pending = this.pending.get(row.RequestId);
        if (!pending || row.Method !== pending.method) return;
        if (row.Type === 'Error') pending.reject(new Error(row.Method + ': ' + (row.Data?.ErrorType || 'Cubism error')));
        if (row.Type === 'Response') pending.resolve(row.Data ?? {});
      });
      socket.addEventListener('close', () => {
        if (this.socket === socket) this.socket = null;
        for (const job of this.pending.values()) {
          if (job.socket === socket) job.reject(new Error('Cubism disconnected during ' + job.method));
        }
      });
      const registration = { Name: pluginName, Path: fileURLToPath(new URL('./server.mjs', import.meta.url)) };
      try {
        const saved = JSON.parse(await readFile(tokenPath, 'utf8'));
        if (typeof saved.token === 'string') registration.Token = saved.token;
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
      const { Token } = await this.request('RegisterPlugin', registration);
      if (typeof Token !== 'string' || !Token.length) throw new Error('Cubism returned no registration token');
      if (Token !== registration.Token) {
        await mkdir(path.dirname(tokenPath), { recursive: true, mode: 0o700 });
        const temp = tokenPath + '.' + randomUUID();
        await writeFile(temp, JSON.stringify({ token: Token }), { mode: 0o600 });
        await rename(temp, tokenPath);
      }
    } catch (error) {
      socket.close();
      if (this.socket === socket) this.socket = null;
      throw error;
    }
  }

  async request(method, data = {}) {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error('Cubism is disconnected');
    const id = randomUUID();
    let timer;
    try {
      return await new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(method + ' timed out')), 6000);
        this.pending.set(id, { socket, method, resolve, reject });
        socket.send(JSON.stringify({ Timestamp: Date.now(), RequestId: id, Type: 'Request', Method: method, Data: data }));
      });
    } finally {
      clearTimeout(timer);
      this.pending.delete(id);
    }
  }

  async status() {
    await this.connect();
    const result = await this.request('GetIsApproval');
    return { endpoint, plugin: pluginName, connected: true, approved: result.Result === true };
  }

  async requireApproval() {
    const status = await this.status();
    if (!status.approved) throw new Error('Cubism is waiting for its Allow checkbox beside ' + pluginName);
  }

  async model(uid) {
    if (uid) return uid;
    const state = await this.request('GetCurrentModelUID');
    if (!state.ModelUID) throw new Error('Select a modeling document or a model track in Cubism');
    return state.ModelUID;
  }
}
