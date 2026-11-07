import { preview } from 'astro';

// Own the in-process server: Astro's agent-aware CLI may detach its server.
export async function withPreview(check, root = process.cwd()) {
  const server = await preview({ root, server: { host: '127.0.0.1', port: 0, open: false } });
  try {
    return await check(`http://127.0.0.1:${server.port}`);
  } finally {
    await server.stop();
  }
}
