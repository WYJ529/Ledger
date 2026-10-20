import { CATEGORIES, isCurrency, LedgerValidationError, TransactionInput, TransactionKind, validateInput } from './domain';
import { isCardRepayment } from './importPolicy';

export const MAX_CSV_ROWS = 200;
const MAX_CSV_CHARACTERS = 2_000_000;

export interface CsvCandidate {
  rowId: string;
  input: TransactionInput;
  warnings: string[];
}

export interface CsvParseResult {
  candidates: CsvCandidate[];
  warnings: string[];
}

interface CsvRow {
  cells: string[];
  line: number;
}

type CsvField = keyof TransactionInput;

const HEADER_ALIASES: Record<CsvField, readonly string[]> = {
  date: ['date', 'transactiondate', 'posteddate', 'postingdate'],
  amount: ['amount', 'transactionamount', 'total'],
  currency: ['currency', 'currencycode', 'curr'],
  merchant: ['merchant', 'merchantname', 'description', 'payee', 'vendor'],
  kind: ['kind', 'type', 'transactiontype', 'direction'],
  category: ['category'],
  notes: ['notes', 'note', 'memo'],
};

function csvError(message: string): never {
  throw new LedgerValidationError({ csv: message });
}

function readRows(source: string): CsvRow[] {
  if (source.length > MAX_CSV_CHARACTERS) csvError('CSV is too large. Limit each import to 2 MB of text and 200 transactions.');
  const text = source.replace(/^\uFEFF/u, '');
  const rows: CsvRow[] = [];
  let cells: string[] = [];
  let cell = '';
  let line = 1;
  let rowLine = 1;
  let state: 'start' | 'plain' | 'quoted' | 'closed' = 'start';

  function finishCell(): void {
    cells.push(cell);
    if (cells.length > 64) csvError(`CSV line ${rowLine} has too many columns (maximum 64).`);
    cell = '';
    state = 'start';
  }

  function finishRow(): void {
    finishCell();
    // Ignore only physically blank lines. A row of empty comma-separated fields is
    // kept as a candidate so its missing values can be corrected in the preview.
    if (!(cells.length === 1 && cells[0]?.trim() === '')) {
      rows.push({ cells, line: rowLine });
      if (rows.length > MAX_CSV_ROWS + 1) csvError(`CSV has more than ${MAX_CSV_ROWS} transactions. Split it into smaller files.`);
    }
    cells = [];
  }

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (state === 'quoted') {
      if (char === '"') {
        if (text[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else {
          state = 'closed';
        }
      } else if (char === '\r' || char === '\n') {
        if (char === '\r' && text[index + 1] === '\n') index += 1;
        cell += '\n';
        line += 1;
      } else {
        cell += char;
      }
      continue;
    }
    if (char === ',') {
      finishCell();
    } else if (char === '\r' || char === '\n') {
      if (char === '\r' && text[index + 1] === '\n') index += 1;
      finishRow();
      line += 1;
      rowLine = line;
    } else if (char === '"') {
      if (state !== 'start') csvError(`Malformed CSV quote on line ${line}. Enclose the entire field in quotes and escape embedded quotes as "".`);
      state = 'quoted';
    } else if (state === 'closed') {
      if (char !== ' ' && char !== '\t') csvError(`Unexpected text after a closing CSV quote on line ${line}.`);
    } else {
      cell += char;
      state = 'plain';
    }
  }
  if (state === 'quoted') csvError(`Unclosed CSV quote starting on line ${rowLine}.`);
  if (cells.length > 0 || cell.length > 0 || state !== 'start') finishRow();
  return rows;
}

function headerField(header: string): CsvField | undefined {
  const normalized = header.trim().toLowerCase().replace(/[\s_-]+/gu, '');
  return (Object.keys(HEADER_ALIASES) as CsvField[]).find(field => HEADER_ALIASES[field].includes(normalized));
}

function csvKind(raw: string): string {
  const normalized = raw.trim().toLowerCase();
  if (normalized === 'debit') return 'expense';
  if (normalized === 'credit') return 'income';
  return normalized || 'expense';
}

/**
 * The supported statement interchange format is CSV with date, amount and merchant
 * (or their documented aliases). Generic dates remain ISO-only; recognized US
 * bank exports have explicit date/sign adapters. Invalid rows stay in preview.
 */
export function parseCsv(text: string, defaultCurrency = 'CAD', statementCurrency?: string): CsvParseResult {
  const fallbackCurrency = (statementCurrency ?? defaultCurrency).trim().toUpperCase();
  if (!isCurrency(fallbackCurrency)) csvError('Choose a supported default currency before importing CSV.');
  const rows = readRows(text);
  const header = rows.shift();
  if (!header) csvError('CSV is empty. Include a header with date, amount, and merchant columns.');
  const rawHeaders = header.cells.map(value => value.trim().toLowerCase().replace(/[\s_-]+/g, ''));
  const amex = ['date', 'description', 'cardmember', 'account#', 'amount'].every(field => rawHeaders.includes(field));
  const chase = ['transactiondate', 'postdate', 'description', 'type', 'amount'].every(field => rawHeaders.includes(field));
  const bank = amex || chase;
  const columns = new Map<CsvField, number>();
  const warnings: string[] = [];
  const unsupported: string[] = [];
  header.cells.forEach((name, index) => {
    const field = headerField(name);
    if (!field) {
      unsupported.push(name.trim() || `unnamed column ${index + 1}`);
    } else if (columns.has(field)) {
      csvError(`CSV has more than one ${field} column. Keep a single column for each field.`);
    } else {
      columns.set(field, index);
    }
  });
  const missing = (['date', 'amount', 'merchant'] as const).filter(field => !columns.has(field));
  if (missing.length > 0) {
    csvError(`CSV is missing required columns: ${missing.join(', ')}. Supported columns are date, amount, merchant, currency, kind, category, notes; description/payee/vendor can replace merchant.`);
  }
  if (unsupported.length > 0) warnings.push(`Unused CSV columns: ${unsupported.join(', ')}.`);
  if (!columns.has('currency')) warnings.push(`No currency column: using ${fallbackCurrency}. Check this currency before importing.`);
  if (bank && !columns.has('currency') && statementCurrency === undefined) csvError('This bank CSV does not identify its currency. Choose the statement currency before previewing.');
  if (!bank && !columns.has('kind')) warnings.push('No kind column: amounts are treated as expenses. Change refunds or deposits to income in the preview.');
  if (rows.length === 0) csvError('CSV contains a header but no transactions.');

  let skippedPayments = 0;
  const candidates = rows.flatMap(row => {
    const rowWarnings: string[] = [];
    function value(field: CsvField, absentDefault = ''): string {
      const position = columns.get(field);
      return position === undefined ? absentDefault : (row.cells[position] ?? '').trim();
    }
    if (row.cells.length !== header.cells.length) {
      rowWarnings.push(`Line ${row.line} has ${row.cells.length} values for ${header.cells.length} columns. Check every field.`);
      const extra = row.cells.slice(header.cells.length);
      if (extra.length > 0) rowWarnings.push(`Values without a column: ${extra.join(' | ')}.`);
    }
    let amount = value('amount');
    let kind = csvKind(value('kind'));
    const bankCredit = amex ? amount.startsWith('-') : !amount.startsWith('-');
    if (bank && bankCredit && (isCardRepayment(value('merchant')) || chase && value('kind').toLowerCase() === 'payment')) {
      skippedPayments++;
      return [];
    }
    if (bank) {
      kind = bankCredit ? 'income' : 'expense';
      amount = amount.replace(/^[+-]/, '');
      if (/^\d{1,3}(,\d{3})+(\.\d{1,2})?$/.test(amount)) amount = amount.replace(/,/g, '');
      if (kind === 'income') rowWarnings.push('Card refund/credit retained for review. Ledger represents credits as income; check the merchant and amount.');
    } else if (amount.startsWith('-')) {
      amount = amount.slice(1);
      if (kind === 'income') rowWarnings.push('This row says income but has a negative amount. It is marked as an expense; confirm the correct direction.');
      else rowWarnings.push('Negative statement amount converted to a positive expense amount.');
      kind = 'expense';
    }
    const input: TransactionInput = {
      // Keep unknown kind values for validation and user correction; never silently
      // rewrite an unsupported statement direction into a valid transaction.
      kind: kind as TransactionKind,
      amount,
      currency: value('currency', fallbackCurrency).toUpperCase(),
      date: bank ? bankCsvDate(value('date')) : value('date'),
      merchant: value('merchant'),
      category: bank ? CATEGORIES.find(category => category.toLowerCase() === value('category').toLowerCase()) ?? 'Other' : value('category', 'Other'),
      notes: value('notes'),
    };
    try {
      validateInput(input);
    } catch (error) {
      if (!(error instanceof LedgerValidationError)) throw error;
      rowWarnings.push(...Object.values(error.issues));
    }
    return { rowId: `row-${row.line}`, input, warnings: rowWarnings };
  });
  if (bank) warnings.unshift(`Skipped ${skippedPayments} credit-card repayments; repayments are transfers, not income.`);
  return { candidates, warnings };
}

function bankCsvDate(raw: string): string {
  const match = raw.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (!match) return raw;
  const iso = `${match[3]}-${match[1]!.padStart(2, "0")}-${match[2]!.padStart(2, "0")}`;
  const parsed = new Date(iso + 'T00:00:00Z');
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === iso ? iso : raw;
}
