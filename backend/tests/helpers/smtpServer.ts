import { createServer, type Socket } from 'node:net';

/** Local SMTP receiver for integration tests; no external provider or real inbox is contacted. */
export async function startSmtpServer() {
  const codes = new Map<string, string>();
  const messages: string[] = [];
  const sockets = new Set<Socket>();
  const server = createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.setEncoding('utf8');
    let buffer = '';
    let receivingData = false;
    let recipient = '';
    socket.write('220 localhost test SMTP\r\n');
    socket.on('data', chunk => {
      buffer += chunk;
      while (buffer.length) {
        if (receivingData) {
          const end = buffer.indexOf('\r\n.\r\n');
          if (end < 0) return;
          const message = buffer.slice(0, end);
          messages.push(message);
          const code = /verification code is ([0-9]{6})/.exec(message)?.[1];
          if (code) codes.set(recipient, code);
          buffer = buffer.slice(end + 5);
          receivingData = false;
          socket.write('250 Message accepted\r\n');
          continue;
        }
        const end = buffer.indexOf('\r\n');
        if (end < 0) return;
        const line = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        if (line.startsWith('EHLO') || line.startsWith('HELO')) socket.write('250 localhost\r\n');
        else if (line.startsWith('RCPT TO:')) { recipient = /<([^>]+)>/.exec(line)?.[1] ?? ''; socket.write('250 OK\r\n'); }
        else if (line === 'DATA') { receivingData = true; socket.write('354 End with dot\r\n'); }
        else if (line === 'QUIT') socket.end('221 Bye\r\n');
        else socket.write('250 OK\r\n');
      }
    });
  });
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('SMTP test server failed to listen.');
  return {
    port: address.port, codes, messages,
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    },
  };
}
