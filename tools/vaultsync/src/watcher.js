import chokidar from 'chokidar';

// 必须忽略 .git：否则每次 commit/fetch 改动 .git 内部文件会再次触发 watcher，形成同步循环
function isIgnored(p) {
  return /(^|[\\/])(\.git|node_modules)([\\/]|$)/.test(p);
}

/**
 * 监听 vault，文件变化后 debounce（持续变化时计时不断重置），
 * 静默期结束后回调一次 onChange(changedCount)。
 */
export function startWatcher({ vaultPath, debounceMs, onChange, log = () => {} }) {
  let timer = null;
  let pendingEvents = 0;

  const fire = () => {
    timer = null;
    const n = pendingEvents;
    pendingEvents = 0;
    onChange(n);
  };

  const reset = () => {
    pendingEvents++;
    if (timer) clearTimeout(timer);
    timer = setTimeout(fire, debounceMs);
  };

  const watcher = chokidar.watch(vaultPath, {
    ignoreInitial: true,
    awaitWriteFinish: { stabilityThreshold: 500, pollInterval: 100 },
    ignored: isIgnored,
  });

  watcher.on('all', (event) => {
    if (event === 'error') return;
    reset();
  });
  watcher.on('error', (err) => log(`Watcher error: ${err?.message || err}`));

  return {
    async close() {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      await watcher.close();
    },
  };
}
