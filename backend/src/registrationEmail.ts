import nodemailer from 'nodemailer';

export interface RegistrationEmailSender {
  sendCode(email: string, code: string): Promise<void>;
}

/** SMTP credentials stay on the backend. Never log the code or the transport error. */
export const smtpRegistrationEmail: RegistrationEmailSender = {
  async sendCode(email, code) {
    const host = process.env.SMTP_HOST?.trim();
    const from = process.env.SMTP_FROM?.trim();
    const port = Number(process.env.SMTP_PORT || '587');
    const user = process.env.SMTP_USER?.trim();
    const pass = process.env.SMTP_PASSWORD;
    if (!host || !from || !Number.isInteger(port) || port < 1 || port > 65535 || Boolean(user) !== Boolean(pass)) {
      throw new Error('Email delivery is not configured.');
    }
    const transport = nodemailer.createTransport({
      host, port, secure: port === 465,
      // Port 587 uses STARTTLS. Permit plaintext only for a local development mail catcher.
      requireTLS: port !== 465 && !(process.env.NODE_ENV !== 'production' && ['localhost', '127.0.0.1', '::1'].includes(host)),
      ...(user && pass ? { auth: { user, pass } } : {}),
      connectionTimeout: 5000, greetingTimeout: 5000, socketTimeout: 10000,
      disableFileAccess: true, disableUrlAccess: true,
    });
    try {
      await transport.sendMail({
        from, to: { address: email, name: '' }, subject: 'Your Ledger verification code',
        text: `Your Ledger verification code is ${code}.\n\nIt expires in 10 minutes. Enter it in the app to finish creating your account.\n\nIf you did not request this code, you can ignore this email.`,
      });
    } finally { transport.close(); }
  },
};
