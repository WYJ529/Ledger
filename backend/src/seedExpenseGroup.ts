import 'dotenv/config';
import { trusted } from 'mongoose';
import { isSupportedCurrencyCode, type Currency } from './currencies';
import { GroupModel } from './groupStore';
import { connectDatabase, disconnectDatabase, mongoUserStore } from './userStore';
import { normalizeUsername } from './validation';

/** Local development bridge until group management/invitations are implemented. */
async function main() {
  try {
    const [name, currency, ...usernames] = process.argv.slice(2);
    const normalizedUsernames = usernames.map(normalizeUsername).filter(username => username !== undefined);
    if (!name?.trim() || name.trim().length > 60 || !currency || !isSupportedCurrencyCode(currency) ||
        usernames.length < 1 || usernames.length > 100 || normalizedUsernames.length !== usernames.length ||
        new Set(normalizedUsernames).size !== usernames.length) {
      throw new Error('Usage: npm run seed:expense-group -- "Group name" CAD owner_username [member_username ...] (1-100 existing accounts)');
    }
    const uri = process.env.MONGODB_URI?.trim();
    if (!uri) throw new Error('Set MONGODB_URI in backend/.env first.');

    try {
      await seedGroup(uri, name.trim(), currency, normalizedUsernames);
    } finally {
      await disconnectDatabase();
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}

async function seedGroup(uri: string, name: string, currency: Currency, usernames: string[]) {
  await connectDatabase(uri);
  const [owner, ...otherMembers] = await Promise.all(usernames.map(async username => {
    const member = await mongoUserStore.findByUsername(username);
    if (!member) throw new Error(`Register this account in the app first: ${username}`);
    return member;
  }));
  if (!owner) throw new Error('An existing owner account is required.');

  const ownerId = owner.id;
  const memberIds = [ownerId, ...otherMembers.map(member => member.id)];
  const existing = await GroupModel.findOne().setOptions({ sanitizeFilter: true })
    .where('name').equals(name)
    .where('ownerId').equals(ownerId)
    .where('memberIds', trusted({ $all: memberIds, $size: memberIds.length }))
    .where('currency').equals(currency);
  const group = existing ?? await GroupModel.create({ name, currency, ownerId, memberIds });
  const displayName = group.name.replace(/[\r\n]/gu, ' ');
  process.stdout.write(`${existing ? 'Existing' : 'Created'} group: ${displayName} (${group.id}). Refresh Group expenses in the app.\n`);
}

void main();
