import { networkInterfaces } from 'node:os';
import { startServer } from './server';

const port = Number(process.env.PORT ?? 8080);
const server = await startServer({ port, host: '0.0.0.0' });

console.log(`Songsterr Jam server listening on port ${server.port}`);
console.log('Enter one of these in the app as the server address:');
for (const addrs of Object.values(networkInterfaces())) {
  for (const a of addrs ?? []) {
    if (a.family === 'IPv4' && !a.internal) console.log(`  ws://${a.address}:${server.port}`);
  }
}
console.log('(Phones must be on the same Wi-Fi, or use a public wss:// address - see README.)');

const shutdown = (): void => {
  void server.close().then(() => process.exit(0));
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
