import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const TOOL_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const PID_FILE = path.join(TOOL_DIR, 'vaultsync.pid');

export function loadConfig(configPath) {
  const file = configPath || process.env.VAULTSYNC_CONFIG || path.join(TOOL_DIR, 'vaultsync.config.json');
  if (!existsSync(file)) {
    throw new Error(`config not found: ${file}`);
  }
  const raw = JSON.parse(readFileSync(file, 'utf8'));
  if (!raw.vaultPath) {
    throw new Error(`config missing "vaultPath": ${file}`);
  }
  const vaultPath = path.resolve(path.dirname(file), raw.vaultPath);
  if (!existsSync(vaultPath)) {
    throw new Error(`vaultPath does not exist: ${vaultPath}`);
  }
  if (!existsSync(path.join(vaultPath, '.git'))) {
    throw new Error(`vaultPath is not a git repository: ${vaultPath}`);
  }

  const debounceSeconds = raw.debounceSeconds ?? 30;
  const pollMinutes = raw.pollMinutes ?? 5;
  if (!(debounceSeconds > 0)) throw new Error('"debounceSeconds" must be > 0');
  if (!(pollMinutes > 0)) throw new Error('"pollMinutes" must be > 0');

  return {
    file,
    vaultPath,
    debounceSeconds,
    pollMinutes,
    debounceMs: debounceSeconds * 1000,
    pollMs: pollMinutes * 60 * 1000,
  };
}
