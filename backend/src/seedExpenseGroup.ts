import 'dotenv/config';
import { isSupportedCurrencyCode } from './currencies';
import { GroupModel } from './groupStore';
import { connectDatabase, disconnectDatabase, mongoUserStore } from './userStore';

/** Local development bridge until group management/invitations are implemented. */
async function main() {
  const [name, currency, ...emails] = process.argv.slice(2);
  const normalizedEmails = emails.map(email => email.trim().toLowerCase());
  if (!name?.trim() || name.trim().length > 60 || !currency || !isSupportedCurrencyCode(currency) ||
      emails.length < 1 || emails.length > 100 || new Set(normalizedEmails).size !== emails.length) {
    throw new Error('Usage: npm run seed:expense-group -- "Group name" CAD owner@example.com [member@example.com ...] (1-100 existing accounts)');
  }
  const uri = process.env.MONGODB_URI?.trim();
  if (!uri) throw new Error('Set MONGODB_URI in backend/.env first.');
  try {
    await connectDatabase(uri);
    const members = await Promise.all(normalizedEmails.map(async email => {
      const member = await mongoUserStore.findByEmail(email);
      if (!member) throw new Error(`Register this account in the app first: ${email}`);
      return member;
    }));
    const memberIds = members.map(user => user.id);
    const groupCurrency = currency;
    const existing = await GroupModel.findOne({ name: name.trim(), ownerId: memberIds[0]!, memberIds: { $all: memberIds, $size: memberIds.length }, currency: groupCurrency });
    const group = existing ?? await GroupModel.create({ name: name.trim(), currency: groupCurrency, ownerId: memberIds[0]!, memberIds });
    console.log(`${existing ? 'Existing' : 'Created'} group: ${group.name} (${group.id}). Refresh Group expenses in the app.`);
  } finally { await disconnectDatabase(); }
}

main().catch(error => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
