import { networkInterfaces } from 'node:os';
import { isIP } from 'node:net';

export function formatLocalTime(date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} GMT${offset >= 0 ? '+' : '-'}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`;
}

export function serverAddress(): { ip: string; source: string } {
  const configured = process.env.SERVER_PUBLIC_IP?.trim();
  if (configured) {
    if (!isIP(configured)) throw new Error('SERVER_PUBLIC_IP must be an IPv4 or IPv6 address');
    return { ip: configured, source: 'configured-public' };
  }
  const addresses = Object.values(networkInterfaces()).flat();
  const local = addresses.find(address => address && !address.internal && address.family === 'IPv4');
  return { ip: local?.address ?? '127.0.0.1', source: 'local-development' };
}
