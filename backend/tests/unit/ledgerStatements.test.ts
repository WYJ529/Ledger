import { parseStatementLines } from '../../src/ledger/statements';
import { parseCsv } from '../../src/ledger/csv';
import { isCardRepayment } from '../../src/ledger/importPolicy';

const lines = (text: string, page: number) => text.trim().split('\n').map(text => ({ text: text.trim(), page }));
// Synthetic examples only. Never put a user's statement or identity in a fixture.
const chase = () => [
  ...lines('chase.com\nPayable in U.S. dollars\nOpening/Closing Date 12/10/24 - 01/09/25\nPayment, Credits -$27.00\nPurchases +$30.00\nFees Charged $2.00\nInterest Charged +$1.00', 1),
  ...lines('ACCOUNT ACCOUNT ACTIVITY ACTIVITY\nPAYMENTS AND OTHER CREDITS\n01/02 Payment Thank You-Mobile -20.00\n01/03 Example merchant refund -7.00\nPURCHASE\n12/28 Example cafe 10.00', 3),
  ...lines('ACCOUNT ACTIVITY (CONTINUED)\n01/04 Example shop 20.00\n01/05 CANADIAN DOLLAR\n28.00 X 0.714285714 (EXCHG RATE)\nFEES CHARGED\n01/07 Annual fee 2.00\nINTEREST CHARGED\n01/09 PURCHASE INTEREST CHARGE 1.00\nTOTAL INTEREST FOR THIS PERIOD $1.00\n2025 Totals Year-to-Date\n01/04 Should never repeat 20.00\nPURCHASES AND REDEMPTIONS\n01/04 Example shop 20.00', 4),
];
const amex = () => [
  ...lines('americanexpress.com\nPayable in US dollars\nClosing Date 06/12/25\nPayments/Credits -$54.00\nNew Charges +$23.00\nFees +$0.00\nInterest Charged +$1.00', 1),
  ...lines('Payments and Credits\nDetail\nPayments Amount\n06/01/25* ONLINE PAYMENT - THANK YOU -$50.00\nForeign\nCredits Spend Amount\n06/02/25 Example refund 28.00 -$4.00\nChina Yuan\nNew Charges\nDetail\n06/03/25 Example cafe $3.00', 3),
  ...lines('Detail Continued\nForeign\nSpend Amount\n06/04/25 Multiline merchant 140.00 $20.00\nChina Yuan\nMerchant description continuation\nFees\nTotal Fees for this Period $0.00', 4),
  ...lines('Interest Charged\n06/12/25 Interest Charge on Purchases $1.00\nTotal Interest Charged for this Period $1.00\n2025 Fees and Interest Totals Year-to-Date\n06/12/25 Repeated interest $1.00', 5),
];

describe('Local card statement extraction', () => {
  test('Chase continuation, duplicate headings, rollover, FX, fees and rewards exclusion reconcile', () => {
    const result = parseStatementLines(chase());
    expect(result.candidates).toHaveLength(5);
    expect(result.candidates.map(row => row.input.date)).toContain('2024-12-28');
    expect(result.candidates.filter(row => row.input.kind === 'income')).toHaveLength(1);
    expect(result.candidates.find(row => row.input.amount === '20.00')?.input.notes).toContain('28.00 CAD');
    expect(result.warnings.join(' ')).toContain('Skipped 1');
    expect(result.candidates.every(row => row.input.currency === 'USD')).toBe(true);
  });
  test('Amex payment/credit sections, foreign columns, continuation and interest reconcile', () => {
    const result = parseStatementLines(amex());
    expect(result.candidates).toHaveLength(4);
    expect(result.candidates.map(row => row.input.amount)).toEqual(['4.00', '3.00', '20.00', '1.00']);
    expect(result.candidates[0]?.warnings.join(' ')).toContain('refund');
    expect(result.candidates[2]?.input.merchant).toBe('Multiline merchant');
    expect(result.candidates[2]?.input.notes).toContain('140.00 CNY');
    expect(result.warnings.join(' ')).toContain('Skipped 1');
  });
  test.each([
    (rows: ReturnType<typeof chase>) => rows.filter(row => !row.text.includes('Example cafe')),
    (rows: ReturnType<typeof chase>) => rows.map(row => ({ ...row, text: row.text.replace('Example cafe 10.00', 'Example cafe unreadable') })),
    (rows: ReturnType<typeof chase>) => rows.map(row => ({ ...row, text: row.text.replace('Purchases +$30.00', 'Purchases +$31.00') })),
    (rows: ReturnType<typeof chase>) => rows.filter(row => !row.text.includes('Fees Charged')),
  ])('rejects incomplete extraction instead of silently accepting a partial statement', change => {
    expect(() => parseStatementLines(change(chase()))).toThrow('reconcile');
  });
  test('requires explicit USD billing evidence and recognized layout', () => {
    expect(() => parseStatementLines(chase().filter(row => !row.text.includes('U.S. dollars')))).toThrow('USD');
    expect(() => parseStatementLines(lines('Unknown Bank\n01/01/25 Shop 10.00', 1))).toThrow('not recognized');
    expect(() => parseStatementLines(chase().filter(row => !row.text.includes('Opening/Closing')))).toThrow();
  });
  test('rejects invalid and out-of-cycle dates', () => {
    expect(() => parseStatementLines(chase().map(row => ({ ...row, text: row.text.replace('12/28 Example', '12/32 Example') })))).toThrow('invalid date');
    expect(() => parseStatementLines(chase().map(row => ({ ...row, text: row.text.replace('12/28 Example', '06/28 Example') })))).toThrow('period');
  });
  test('recognizes repayment labels without treating a payment-service merchant as a repayment', () => {
    expect(isCardRepayment('AUTOPAY PAYMENT - THANK YOU')).toBe(true);
    expect(isCardRepayment('PAYMENT THANK YOU-MOBILE')).toBe(true);
    expect(isCardRepayment('Example Payment Solutions')).toBe(false);
    expect(isCardRepayment('Online Store')).toBe(false);
  });
});

describe('US bank CSV exports', () => {
  const header = 'Date,Description,Card Member,Account #,Amount\n';
  test('Amex requires chosen currency, normalizes dates/signs and skips only repayments', () => {
    const csv = header + '06/02/2025,ONLINE PAYMENT - THANK YOU,Private Name,12345,-50.00\n06/03/2025,Shop refund,Private Name,12345,-4.00\n06/04/2025,Example Payment Solutions,Private Name,12345,20.00';
    expect(() => parseCsv(csv, 'CAD')).toThrow('Choose the statement currency');
    const result = parseCsv(csv, 'CAD', 'USD');
    expect(result.candidates).toHaveLength(2);
    expect(result.candidates[0]?.input).toMatchObject({ date: '2025-06-03', amount: '4.00', currency: 'USD', kind: 'income' });
    expect(result.candidates[1]?.input.kind).toBe('expense');
    expect(result.warnings.join(' ')).toContain('Skipped 1');
    expect(JSON.stringify(result)).not.toMatch(/Private Name|12345/);
  });
  test('Chase negative purchase and positive return use opposite signs from Amex', () => {
    const result = parseCsv('Transaction Date,Post Date,Description,Category,Type,Amount,Memo\n06/02/2025,06/03/2025,Store,Groceries,Sale,-10.00,\n06/03/2025,06/04/2025,Store,Groceries,Return,2.00,\n06/05/2025,06/05/2025,Thank you,,Payment,8.00,', 'CAD', 'USD');
    expect(result.candidates.map(row => row.input.kind)).toEqual(['expense', 'income']);
    expect(result.candidates[0]?.input.date).toBe('2025-06-02');
    expect(result.candidates.every(row => row.input.category === 'Other')).toBe(true);
  });
  test('invalid dates stay invalid and generic CSV does not acquire US assumptions', () => {
    expect(parseCsv(header + '02/30/2025,Shop,A,1,2.00', 'CAD', 'USD').candidates[0]?.input.date).toBe('02/30/2025');
    expect(parseCsv('date,merchant,amount\n06/02/2025,Shop,-2.00').candidates[0]?.input).toMatchObject({ date: '06/02/2025', kind: 'expense' });
  });
  test('accepts valid grouping but preserves malformed commas and repayment reversals for review', () => {
    const csv = header + '06/02/2025,Shop,A,1,"1,234.50"\n06/03/2025,Shop,A,1,"12,34.50"\n06/04/2025,ONLINE PAYMENT REVERSAL,A,1,20.00';
    const result = parseCsv(csv, 'CAD', 'USD');
    expect(result.candidates.map(row => row.input.amount)).toEqual(['1234.50', '12,34.50', '20.00']);
    expect(result.candidates[1]?.warnings.length).toBeGreaterThan(0);
    expect(result.candidates[2]?.input.kind).toBe('expense');
  });
});
