import { randomUUID } from 'node:crypto';
import type { DocumentFile, Extraction, ImportCandidate } from './providers';
import { LedgerServiceError } from './providers';
import { isCardRepayment } from './importPolicy';

type StatementLine = { text: string; page: number };
type Section = 'payments' | 'credits' | 'purchases' | 'interest' | 'fees';
const foreignNames: Record<string, string> = {
  'CANADIAN DOLLAR': 'CAD', 'CANADA DOLLAR': 'CAD', 'CHINA YUAN': 'CNY',
  'CHINESE YUAN': 'CNY', 'YUAN RENMINBI': 'CNY', 'JAPANESE YEN': 'JPY',
  'EURO': 'EUR', 'EUROPEAN UNION EURO': 'EUR', 'BRITISH POUND': 'GBP',
  'HONG KONG DOLLAR': 'HKD',
};
function minor(text: string): number {
  const value = text.replace(/[$,\s]/g, '');
  if (!/^-?\d+\.\d{2}$/.test(value)) throw new LedgerServiceError(422, 'A statement amount is unreadable. Try CSV or review with AI.');
  const result = Math.round(Number(value) * 100);
  if (!Number.isSafeInteger(result)) throw new LedgerServiceError(422, 'A statement amount is too large.');
  return result;
}
function money(value: number): string { return (Math.abs(value) / 100).toFixed(2); }
function usDate(raw: string, closing?: string): string {
  const parts = raw.split('/').map(Number);
  const [month = 0, day = 0] = parts;
  let year = parts[2];
  if (year !== undefined && year < 100) year += 2000;
  if (year === undefined) {
    if (!closing) throw new LedgerServiceError(422, 'The statement closing date is missing; the transaction year cannot be inferred safely.');
    year = Number(closing.slice(0, 4));
    if (month > Number(closing.slice(5, 7))) year--;
  }
  const iso = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  const parsed = new Date(iso + 'T00:00:00Z');
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== iso) throw new LedgerServiceError(422, 'The statement contains an invalid date.');
  return iso;
}

/** PDF text is reconstructed by position, so a foreign-spend column cannot become a billed amount. */
export async function readStatementLines(file: DocumentFile): Promise<StatementLine[]> {
  if (file.mimeType !== 'application/pdf') throw new LedgerServiceError(415, 'Local statement preview supports text PDFs. Choose AI extraction for receipt images.');
  const { getDocument } = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const task = getDocument({ data: new Uint8Array(file.bytes), useSystemFonts: false, disableFontFace: true, verbosity: 0 });
  try {
    const document = await task.promise;
    if (document.numPages > 20) throw new LedgerServiceError(422, 'Preview at most 20 PDF pages at a time.');
    const lines: StatementLine[] = [];
    let characters = 0;
    for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber++) {
      const page = await document.getPage(pageNumber);
      const content = await page.getTextContent();
      const items = content.items.filter(item => 'str' in item).map(item => ({ text: item.str, x: item.transform[4] as number, y: item.transform[5] as number }));
      characters += items.reduce((sum, item) => sum + item.text.length, 0);
      if (characters > 500000) throw new LedgerServiceError(422, 'This PDF contains too much text. Use a shorter statement.');
      items.sort((a, b) => b.y - a.y || a.x - b.x);
      const rows: typeof items[] = [];
      for (const item of items) {
        const previous = rows.at(-1);
        if (previous && Math.abs(previous[0]!.y - item.y) < 2.5) previous.push(item);
        else rows.push([item]);
      }
      for (const row of rows) {
        row.sort((a, b) => a.x - b.x);
        const text = row.map(item => item.text).join(' ').replace(/\s+/g, ' ').trim();
        if (text) lines.push({ text, page: pageNumber });
      }
      page.cleanup();
    }
    return lines;
  } catch (error) {
    if (error instanceof LedgerServiceError) throw error;
    throw new LedgerServiceError(422, 'This PDF cannot be read locally (it may be scanned, encrypted or damaged). Use an unlocked text PDF, CSV, or explicitly choose AI extraction.');
  } finally { await task.destroy(); }
}

/** Known US card layouts only; fail closed if detail does not reconcile to the statement summary. */
export function parseStatementLines(lines: StatementLine[]): Extraction {
  const all = lines.map(line => line.text).join('\n');
  const compact = all.replace(/\s+/g, '');
  const chase = /chase\.com/i.test(all) && /Opening\/ClosingDate/i.test(compact);
  const amex = /americanexpress\.com/i.test(compact) && /ClosingDate/i.test(compact);
  if (!chase && !amex) throw new LedgerServiceError(422, 'Local preview supports US Chase and American Express text statements. This layout is not recognized; choose CSV or explicitly use AI extraction.');
  if (!/U\.?S\.?\s+dollars/i.test(all)) throw new LedgerServiceError(422, 'The statement does not establish USD billing. Use CSV with an explicit currency or review with AI.');
  const closingMatch = chase ? compact.match(/Opening\/ClosingDate\d{2}\/\d{2}\/\d{2}-(\d{2}\/\d{2}\/\d{2})/i)
    : compact.match(/ClosingDate(\d{2}\/\d{2}\/\d{2})/i);
  if (!closingMatch?.[1]) throw new LedgerServiceError(422, 'The statement closing date could not be read. Use CSV or AI review.');
  const closing = usDate(closingMatch[1]);
  const candidates: ImportCandidate[] = [];
  const totals = { payments: 0, credits: 0, purchases: 0, interest: 0, fees: 0 };
  let section: Section | undefined;
  let active = false;
  let skippedPayments = 0;
  let unresolved = 0;
  let last: ImportCandidate | undefined;
  let foreignAmount = '';
  let foreignCurrency = '';
  const bank = chase ? 'Chase' : 'American Express';
  // Amex prefixes payment/credit rows with the cardmember's name ("JANE DOE DELTA AIR LINES"); the summary
  // lists each name before its account ending ("Jane Doe 1-23456"). Strip it so the merchant stays a merchant.
  const cardmembers = amex ? [...new Set(lines.flatMap(({ text }) => {
    const name = text.match(/^([A-Za-z][A-Za-z .'-]{1,40}?)\s+\d-\d{5}\b/)?.[1]?.trim();
    return name && !/\bEnding$/i.test(name) ? [name.toUpperCase()] : [];
  }))].sort((a, b) => b.length - a.length) : [];
  const withoutCardmember = (merchant: string) => {
    const name = cardmembers.find(member => merchant.toUpperCase().startsWith(member + ' '));
    return name ? merchant.slice(name.length).trim() : merchant;
  };
  for (const { text, page } of lines) {
    const normalized = text.toUpperCase().replace(/\b(\w+)(?:\s+\1\b)+/g, '$1').replace(/\s+/g, '');
    if (chase) {
      if (normalized.startsWith('ACCOUNTACTIVITY')) active = true;
      if (/TOTALSYEAR-TO-DATE|INTERESTCHARGES|PURCHASESANDREDEMPTIONS/.test(normalized)) { active = false; last = undefined; }
      if (!active) continue;
      if (normalized === 'PAYMENTSANDOTHERCREDITS') section = 'credits';
      if (normalized === 'PURCHASE' || normalized === 'PURCHASES') section = 'purchases';
      if (normalized === 'INTERESTCHARGED') section = 'interest';
      if (normalized === 'FEESCHARGED' || normalized === 'FEES') section = 'fees';
    } else {
      if (/^Payments Amount$/i.test(text)) { active = true; section = 'payments'; }
      if (/^Credits (?:Spend )?Amount$/i.test(text)) { active = true; section = 'credits'; }
      if (/^(?:Detail|Detail Continued)/i.test(text)) active = true;
      if (normalized === 'NEWCHARGES' || normalized === 'NEWCHARGES(CONTINUED)') { active = true; section = 'purchases'; }
      if (normalized === 'INTERESTCHARGED') { active = true; section = 'interest'; }
      if (normalized === 'FEES') { active = true; section = 'fees'; }
      if (/^\d{4}FEESANDINTEREST|INTERESTCHARGECALCULATION|MILESEARNED|IMPORTANTNOTICES/.test(normalized)) { active = false; last = undefined; }
      if (!active) continue;
    }
    // Foreign currency continuation lines never constitute additional transactions.
    const namedCurrency = Object.entries(foreignNames).find(([name]) => text.toUpperCase().includes(name));
    if (namedCurrency && last) {
      foreignCurrency = namedCurrency[1];
      if (foreignAmount) {
        last.input.notes += ` Foreign spend: ${foreignAmount} ${foreignCurrency}; ledger amount is billed USD.`;
        foreignAmount = '';
      }
      continue;
    }
    if (chase && last && /\(EXCHG RATE\)/i.test(text)) {
      const original = text.match(/^([\d,]+\.\d{2})\s+X\s+([\d.]+)/i);
      if (original) last.input.notes += ` Foreign spend: ${original[1]} ${foreignCurrency || "(currency unreadable)"}; card rate ${original[2]}.`;
      continue;
    }
    const dated = text.match(chase ? /^(\d{2}\/\d{2})\s+(.+)$/ : /^(\d{2}\/\d{2}\/\d{2})\*?\s+(.+)$/);
    if (!dated || !section) continue;
    const detail = dated[2]!;
    const amountMatch = detail.match(/\s+(-?\$?\s*\d[\d,]*\.\d{2})$/);
    if (!amountMatch) { unresolved++; last = undefined; continue; }
    const signed = minor(amountMatch[1]!);
    let merchant = detail.slice(0, amountMatch.index).trim();
    foreignAmount = '';
    foreignCurrency = '';
    if (amex) {
      const original = merchant.match(/\s+(\d[\d,]*\.\d{2})$/);
      if (original) { foreignAmount = original[1]!; merchant = merchant.slice(0, original.index).trim(); }
      merchant = withoutCardmember(merchant);
    }
    const repayment = signed < 0 && (section === 'payments' || isCardRepayment(merchant));
    const actualSection = repayment ? 'payments' : signed < 0 ? 'credits' : section;
    totals[actualSection] += signed;
    if (repayment) { skippedPayments++; last = undefined; continue; }
    if (signed === 0) { last = undefined; continue; }
    const date = usDate(dated[1]!, closing);
    // A transaction can precede the opening date because posting occurs later.
    const age = Date.parse(closing) - Date.parse(date);
    if (age < 0 || age > 65 * 86400000) throw new LedgerServiceError(422, 'A detail date falls outside the expected statement period. Use CSV or AI review.');
    last = { rowId: randomUUID(), input: {
      kind: signed < 0 ? 'income' : 'expense', amount: money(signed), currency: 'USD', date,
      merchant, category: 'Other', notes: `${bank} statement; billed USD; page ${page}.`,
    }, warnings: signed < 0 ? ['Card refund/credit retained for review. Ledger currently represents credits as income; check the merchant and amount.'] : [] };
    if (merchant.length > 120) last.warnings.push('Shorten this statement description to 120 characters before importing.');
    candidates.push(last);
    if (candidates.length > 200) throw new LedgerServiceError(422, 'The statement contains more than 200 transactions. Split it into smaller statements.');
  }
  const summary = lines.filter(line => line.page === 1).map(line => line.text).join('\n');
  function expected(pattern: RegExp): number | undefined {
    const match = summary.match(pattern);
    return match ? minor(match[1]!) : undefined;
  }
  const purchases = expected(chase
    ? /Purchases\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i
    : /New Charges\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i);
  const credits = expected(chase
    ? /Payment,?\s*Credits\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i
    : /Payments\/Credits\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i);
  const interest = expected(/Interest Charged\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i);
  const fees = expected(chase
    ? /Fees Charged\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i
    : /Fees\s*[+]?\s*(-?\$?\s*\d[\d,]*\.\d{2})/i);
  if (unresolved || purchases === undefined || credits === undefined || interest === undefined || fees === undefined ||
      purchases !== totals.purchases || credits !== totals.payments + totals.credits || interest !== totals.interest || fees !== totals.fees) {
    throw new LedgerServiceError(422, 'Local extraction could not reconcile every transaction with the statement totals. Nothing was imported. Use the bank CSV or explicitly choose AI extraction and review all rows.');
  }
  return { candidates, warnings: [
    `${bank} local PDF preview: transaction totals match the statement summary. No document was sent to OpenAI.`,
    `Skipped ${skippedPayments} credit-card repayment${skippedPayments === 1 ? "" : "s"}; repayments are transfers, not income.`,
    'Amounts use billed USD to match the card statement; readable foreign-spend details are retained in notes. Review before saving.',
  ] };
}

export async function extractStatement(file: DocumentFile): Promise<Extraction> {
  return parseStatementLines(await readStatementLines(file));
}
