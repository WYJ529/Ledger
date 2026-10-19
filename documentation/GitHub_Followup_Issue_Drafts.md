# Ledger follow-up issue drafts

Status: Published in English through the owner's authenticated GitHub browser session.

- #37: https://github.com/WYJ529/Ledger/issues/37 — Redesign the home screen with bottom navigation
- #38: https://github.com/WYJ529/Ledger/issues/38 — Improve visual consistency and usability across Ledger screens
- #39: https://github.com/WYJ529/Ledger/issues/39 — Require uppercase and lowercase letters in registration passwords
- #40: https://github.com/WYJ529/Ledger/issues/40 — Validate email domains during account registration
- #41: https://github.com/WYJ529/Ledger/issues/41 — Add confirmation emails and email verification for password accounts
- #42: https://github.com/WYJ529/Ledger/issues/42 — Add common description suggestions to the expense form

Each published issue includes its scope and acceptance criteria. Issue #38 also links to #37, and #41 links to #40.

Existing issue #18 already tracks group balances and settlements; do not create a duplicate.

## 1. Redesign the home screen with bottom navigation

### Problem
Ledger currently uses large menu buttons on the home screen. Users need a clearer way to move between personal accounting, groups, and their profile.

### Scope
Replace the home menu with a persistent bottom navigation bar containing three destinations:
- **Personal**: connect to the team's personal accounting flow.
- **Groups**: group list, invitations, and group expenses.
- **Profile**: account information, settings, and logout.

Each destination should have an icon and a text label. Integrate the existing features rather than duplicating their implementation.

### Acceptance criteria
- [ ] Personal, Groups, and Profile can be opened from the bottom navigation bar.
- [ ] The selected destination is clearly indicated.
- [ ] The large home menu buttons are replaced by the new navigation structure.
- [ ] Navigation state behaves correctly after switching tabs and rotating the device.
- [ ] Back from Add/Edit expense returns to the group expense page; back from a group returns to the group list.
- [ ] Keyboard and system insets do not obscure navigation or form actions.

### Related work
Personal expense summary is tracked in #35; group expense summary is tracked in #19. General visual consistency should be handled separately from this navigation change.

---

## 2. Improve visual consistency and usability across Ledger screens

### Problem
Ledger's current screens need a more consistent visual style and clearer layouts before further product refinement.

### Scope
Polish the existing authentication, profile, group list, group detail, invitation, and expense forms using a consistent set of typography, spacing, colors, cards, and button styles. Preserve the approved Ledger name and icon.

The home/bottom navigation redesign is a separate task. This issue does not add new personal accounting or group summary business logic.

### Acceptance criteria
- [ ] Shared screens use consistent typography, spacing, colors, and action styles.
- [ ] Primary and secondary actions are visually distinguishable, with destructive actions clearly identified.
- [ ] Forms display readable validation messages and retain required-field indicators.
- [ ] Loading, empty, success, and error states are clear and consistent.
- [ ] Content remains usable on small screens, with the keyboard open and with larger system text.
- [ ] Existing group navigation, permissions, invitations, and expense editing continue to work.

### Related work
Keep the scope distinct from the existing group summary (#19) and personal expense summary (#35) issues.

---

## 3. Require uppercase and lowercase letters in registration passwords

### Problem
Email/password registration currently accepts passwords without a combination of uppercase and lowercase letters. The frontend and backend need to enforce the requested password rule consistently.

### Scope
Require at least one uppercase letter and one lowercase letter for new email/password accounts, while retaining the existing password length limits. Continue to support digits and special characters. Explain the requirements before submission and provide specific validation feedback.

### Acceptance criteria
- [ ] New email/password accounts require at least one uppercase and one lowercase letter.
- [ ] The existing 8–128 character limits remain enforced.
- [ ] Frontend guidance, frontend validation, and backend validation agree.
- [ ] All-lowercase, all-uppercase, and otherwise invalid passwords are rejected with clear feedback.
- [ ] Valid mixed-case passwords and password confirmation work correctly.
- [ ] Existing accounts can still log in using their current passwords.
- [ ] Google authentication does not require a separate Ledger password.
- [ ] Tests cover accepted and rejected passwords and direct API submissions.

### Notes
Additional mandatory digit/special-character rules can be decided explicitly later; this issue captures the requested uppercase/lowercase requirement.

---

## 4. Validate email domains during account registration

### Problem
The registration flow needs stronger feedback for invalid email domains or suffixes. Basic email syntax alone does not establish that a domain is valid or that a user owns the mailbox.

### Scope
Define and implement a domain validation policy for email/password registration. Support legitimate personal, university, and company/custom domains rather than limiting registration to a short list of popular providers. Keep frontend guidance and backend validation consistent.

### Acceptance criteria
- [ ] The accepted domain/suffix policy is documented before implementation.
- [ ] Invalid email syntax and domains rejected by the policy produce actionable feedback.
- [ ] Valid university, company, custom, and multi-part domains are supported.
- [ ] Backend validation cannot be bypassed with a direct registration request.
- [ ] If domain validation uses a network lookup, transient lookup failures are handled distinctly from confirmed invalid domains.
- [ ] Tests cover valid, invalid, and policy boundary cases.
- [ ] Domain validation does not mark an email address as verified or claim mailbox ownership.

### Related work
Confirmation email and mailbox ownership verification should be tracked separately.

---

## 5. Add confirmation emails and email verification for password accounts

### Problem
Creating an email/password account does not currently confirm that the user owns the supplied email address.

### Scope
Send a confirmation email after email/password registration. Provide a confirmation flow, verified/unverified account status, and a way to resend the email. Define how an unverified account behaves before enforcing any access restrictions.

### Acceptance criteria
- [ ] Registration creates an unverified email/password account and attempts to send a confirmation email.
- [ ] A valid confirmation link verifies the account's email address.
- [ ] Confirmation tokens are securely generated, expire, and cannot be reused after successful verification.
- [ ] Invalid, expired, and already-used links display clear outcomes.
- [ ] Users can see their verification status and request another confirmation email, with resend throttling.
- [ ] Email delivery failures are recoverable without creating duplicate accounts.
- [ ] The behavior for existing password accounts and unverified accounts is documented.
- [ ] The existing Google authentication flow continues to respect Google's verified-email claim.
- [ ] Email provider credentials and configuration stay outside source control.
- [ ] Tests cover confirmation, expiration, reuse, resending, and delivery failures.

### Notes
Email format/domain validation is a separate concern and must not substitute for confirmation of mailbox ownership.

---

## 6. Add common description suggestions to the expense form

### Problem
Users repeatedly type common descriptions when recording group expenses.

### Scope
Show selectable suggestions near the Description field in Add expense, such as **Dinner**, **Groceries**, **Transport**, and **Rent**. Keep free-text entry available.

### Acceptance criteria
- [ ] Common description suggestions are available when entering a new expense.
- [ ] Selecting a suggestion fills the Description field.
- [ ] Users can edit the selected text or enter a completely custom description.
- [ ] Suggestions do not silently overwrite existing typed text.
- [ ] The existing description length and validation rules are preserved.
- [ ] Suggestions remain accessible and usable on small screens and with the keyboard open.
- [ ] Editing an existing expense preserves its saved description until the user changes it.
