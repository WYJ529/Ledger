"""Render the use case diagram (Figure 1) and dependency diagram (Figure 2) as HTML pages.

Usage: python structure_diagrams.py <output-dir>
Each page is 3400 px wide; screenshot it at the printed height (for example with a
headless browser) to produce the PNG embedded in Requirements_and_Design.md.
"""
import sys
from pathlib import Path
from html import escape

W = 3400
TEAL, INK, GREY, LINE, FILL, PALE = "#0f7f86", "#1e2a3a", "#5d6b78", "#a9b6c2", "#eef3f6", "#f7fafb"
DEFS = (f'<defs>'
        f'<marker id="solid" markerWidth="16" markerHeight="16" refX="14" refY="6" orient="auto" markerUnits="userSpaceOnUse">'
        f'<path d="M0,0 L14,6 L0,12 z" fill="{TEAL}"/></marker>'
        f'<marker id="open" markerWidth="16" markerHeight="16" refX="14" refY="6" orient="auto" markerUnits="userSpaceOnUse">'
        f'<path d="M0,0 L14,6 L0,12" fill="none" stroke="{GREY}" stroke-width="2.5"/></marker>'
        f'<marker id="general" markerWidth="30" markerHeight="30" refX="24" refY="12" orient="auto" markerUnits="userSpaceOnUse">'
        f'<path d="M0,0 L24,12 L0,24 z" fill="#ffffff" stroke="{INK}" stroke-width="2.5"/></marker>'
        f'</defs>')


def text(x, y, s, size=30, color=INK, weight=400, anchor="start", italic=False):
    style = ' font-style="italic"' if italic else ''
    return (f'<text x="{x:.0f}" y="{y:.0f}" fill="{color}" font-size="{size}" font-weight="{weight}" '
            f'text-anchor="{anchor}"{style}>{escape(s)}</text>')


def lines_text(x, y, rows, size=28, color=INK, weight=400, anchor="middle", gap=1.25):
    return "".join(text(x, y + i * size * gap, row, size, color, weight, anchor) for i, row in enumerate(rows))


def header(title, tag):
    return (text(24, 66, title, 58, INK, 700) + text(W - 24, 62, tag, 44, TEAL, 700, "end")
            + f'<line x1="24" y1="92" x2="{W - 24}" y2="92" stroke="{TEAL}" stroke-width="3"/>')


def page(body, height):
    return (f'<!doctype html><html><head><meta charset="utf-8"><style>html,body{{margin:0;padding:0;background:#fff}}</style></head>'
            f'<body><svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{height}" viewBox="0 0 {W} {height}" '
            f'font-family="Arial, Helvetica, sans-serif"><rect width="{W}" height="{height}" fill="#ffffff"/>{DEFS}{body}</svg></body></html>')


def path(points, color=TEAL, dashed=False, marker="solid", width=3):
    d = "M" + " L".join(f"{x:.0f},{y:.0f}" for x, y in points)
    dash = ' stroke-dasharray="12 9"' if dashed else ''
    end = f' marker-end="url(#{marker})"' if marker else ''
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"{dash}{end}/>'


# ---------------------------------------------------------------- Figure 1

OVAL_W, OVAL_H, PITCH, BOX_HEAD = 760, 76, 104, 66


def actor(x, y, name):
    """Stick figure centred on x with the head top at y; returns (svg, attach_y)."""
    svg = (f'<circle cx="{x}" cy="{y + 26}" r="26" fill="#ffffff" stroke="{INK}" stroke-width="3"/>'
           f'<line x1="{x}" y1="{y + 52}" x2="{x}" y2="{y + 120}" stroke="{INK}" stroke-width="3"/>'
           f'<line x1="{x - 44}" y1="{y + 76}" x2="{x + 44}" y2="{y + 76}" stroke="{INK}" stroke-width="3"/>'
           f'<path d="M{x - 38},{y + 172} L{x},{y + 120} L{x + 38},{y + 172}" fill="none" stroke="{INK}" stroke-width="3"/>'
           + lines_text(x, y + 214, name.split("\n"), 30, INK, 700))
    return svg, y + 86


def external(cx, cy, name):
    rows = name.split("\n")
    h = 70 + 34 * len(rows)
    svg = (f'<rect x="{cx - 200}" y="{cy - h / 2:.0f}" width="400" height="{h}" rx="12" fill="#fff8ea" stroke="#d9b86c" stroke-width="2.5"/>'
           + text(cx, cy - h / 2 + 40, "«external system»", 24, GREY, 400, "middle", True)
           + lines_text(cx, cy - h / 2 + 76, rows, 30, INK, 700))
    return svg, h


def use_case_diagram():
    stacks = {
        "left": (980, [
            ("Authentication and Account Management", [
                ("signin", "Sign In"), ("register", "Register an Account"),
                ("account", "View and Edit Account Information"), ("signout", "Sign Out")]),
            ("Feature 1 — Personal Account Management", [
                ("manual", "Manually Add an Entry"), ("records", "Review and Edit Personal Records"),
                ("fx", "Convert Currency"), ("statement", "Import a Bank Statement"),
                ("uc2", "Resolve Duplicate Candidates (UC-02)"), ("uc1", "Import and Review a Receipt (UC-01)"),
                ("saves", "Save and Load Cloud Saves")])]),
        "right": (2420, [
            ("Feature 2 — Expense Group Management", [
                ("create", "Create an Expense Group"), ("invite", "Invite and Join Group Members"),
                ("rename", "Rename an Expense Group"), ("record", "Record and View a Shared Expense"),
                ("edit", "Edit or Delete a Shared Expense"), ("method", "Choose an Extended Split Method"),
                ("uc3", "Reuse and Split a Personal Bill (UC-03)"), ("leave", "Leave or Remove Members"),
                ("delete", "Delete an Expense Group"), ("repay", "Record and Confirm a Repayment (planned)"),
                ("archive", "Archive an Expense Group (planned)")]),
            ("Feature 3 — Let's Go Dutch", [
                ("uc4", "Claim or Unclaim Receipt Items Live (UC-04)"), ("view", "View Live Allocations and Totals"),
                ("uc5", "Recover Live State After Reconnect (UC-05)"), ("start", "Start a Split Session"),
                ("finalize", "Finalize a Split Session")])]),
    }
    ovals, back, front = {}, [], []
    bottom = 0
    for cx, groups in stacks.values():
        y = 150
        for title, cases in groups:
            top = y
            y += BOX_HEAD
            for key, label in cases:
                cy = y + OVAL_H / 2
                ovals[key] = (cx, cy)
                planned = "(planned)" in label
                dash = ' stroke-dasharray="12 8"' if planned else ''
                weight = 700 if "(UC-" in label else 400
                front.append(f'<ellipse cx="{cx}" cy="{cy:.0f}" rx="{OVAL_W / 2}" ry="{OVAL_H / 2}" fill="#ffffff" '
                             f'stroke="{GREY if planned else TEAL}" stroke-width="2.5"{dash}/>'
                             + text(cx, cy + 10, label, 28, GREY if planned else INK, weight, "middle"))
                y += PITCH
            y += 14
            back.append(f'<rect x="{cx - OVAL_W / 2 - 34}" y="{top}" width="{OVAL_W + 68}" height="{y - top}" rx="16" '
                        f'fill="{PALE}" stroke="{LINE}" stroke-width="2.5"/>' + text(cx, top + 44, title, 32, TEAL, 700, "middle"))
            y += 36
        bottom = max(bottom, y)

    def left_edge(key):
        x, y = ovals[key]
        return x - OVAL_W / 2, y

    def right_edge(key):
        x, y = ovals[key]
        return x + OVAL_W / 2, y

    links = []
    # Left-hand human actors and their generalization.
    user_svg, user_y = actor(200, 330, "User")
    auth_svg, auth_y = actor(200, 1010, "Authenticated\nUser")
    front += [user_svg, auth_svg]
    links.append(path([(200, 1008), (200, 600)], INK, marker="general", width=2.5))
    for key in ("signin", "register", "manual", "records", "fx", "statement", "uc2"):
        links.append(path([(246, user_y), left_edge(key)], INK, marker=None, width=2))
    for key in ("account", "signout", "uc1", "saves"):
        links.append(path([(246, auth_y), left_edge(key)], INK, marker=None, width=2))

    # Right-hand group actors.
    rauth_svg, rauth_y = actor(3200, 190, "Authenticated\nUser")
    member_svg, member_y = actor(3200, 900, "Group Member")
    owner_svg, owner_y = actor(3200, 1520, "Group Owner")
    front += [rauth_svg, member_svg, owner_svg]
    links.append(path([(3200, 898), (3200, 460)], INK, marker="general", width=2.5))
    links.append(path([(3200, 1518), (3200, 1160)], INK, marker="general", width=2.5))
    for key in ("create", "invite"):
        links.append(path([(3154, rauth_y), right_edge(key)], INK, marker=None, width=2))
    for key in ("invite", "rename", "record", "edit", "method", "uc3", "leave", "repay", "uc4", "view", "uc5"):
        links.append(path([(3154, member_y), right_edge(key)], INK, marker=None, width=2))
    for key in ("edit", "leave", "delete", "archive", "start", "finalize"):
        links.append(path([(3154, owner_y), right_edge(key)], INK, marker=None, width=2))

    # External systems sit between the two stacks, outside every subject box.
    for name, cy, keys in (("Google\nAuthentication", 360, ("signin", "register")),
                           ("OpenAI Document\nRecognition", 900, ("statement", "uc1")),
                           ("Exchange-rate Service\n(Frankfurter / ECB)", 1180, ("fx", "uc2"))):
        svg, _ = external(1700, cy, name)
        front.append(svg)
        for key in keys:
            links.append(path([right_edge(key), (1500, cy)], INK, marker=None, width=2))

    # «include»: both document imports include duplicate resolution.
    ux, uy = ovals["uc2"]
    for key, sign in (("statement", -1), ("uc1", 1)):
        _, ky = ovals[key]
        links.append(path([(ux - 200, ky - sign * OVAL_H / 2), (ux - 200, uy + sign * OVAL_H / 2)], GREY, True, "open", 2.5))
    front.append(text(ux - 186, uy - 46, "«include»", 24, GREY, 400, "start", True))
    front.append(text(ux - 186, uy + 64, "«include»", 24, GREY, 400, "start", True))

    legend_y = bottom + 20
    legend = (text(560, legend_y + 30, "Solid line: actor takes part in the use case.   Hollow triangle: generalization "
                   "(inherits the parent's use cases).   Dashed arrow: «include».   Dashed oval: planned, not implemented.   "
                   "Bold: formal use case.", 26, GREY))
    height = legend_y + 70
    body = header("Ledger Use Case Diagram", "CP2") + "".join(back) + "".join(links) + "".join(front) + legend
    return page(body, height), height


# ---------------------------------------------------------------- Figure 2

def box(x, y, w, h, title, rows=(), fill=FILL, stroke="#9fb3c2"):
    titles = title.split("\n")
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{fill}" stroke="{stroke}" stroke-width="3"/>'
            + lines_text(x + w / 2, y + 54, titles, 38, INK, 700, gap=1.15)
            + lines_text(x + w / 2, y + 96 + 44 * (len(titles) - 1), rows, 26, GREY))


def cylinder(x, y, w, h, title, rows=()):
    rx, ry = w / 2, 26
    return (f'<path d="M{x},{y + ry} A{rx},{ry} 0 0 1 {x + w},{y + ry} L{x + w},{y + h - ry} A{rx},{ry} 0 0 1 {x},{y + h - ry} Z" '
            f'fill="{FILL}" stroke="#9fb3c2" stroke-width="3"/>'
            f'<ellipse cx="{x + rx}" cy="{y + ry}" rx="{rx}" ry="{ry}" fill="#ffffff" stroke="#9fb3c2" stroke-width="3"/>'
            + text(x + rx, y + 104, title, 38, INK, 700, "middle") + lines_text(x + rx, y + 146, rows, 26, GREY))


def label(x, y, rows, anchor="middle", color=TEAL):
    return lines_text(x, y, rows, 26, color, 400, anchor, 1.2)


def dependency_diagram():
    cx, cw, ch = 1060, 640, 176
    comps = {"bi": 300, "um": 610, "gim": 920, "gbs": 1230}
    right = cx + cw
    parts = [header("Ledger Dependency Diagram", "CP2"),
             f'<rect x="990" y="170" width="1450" height="1340" rx="22" fill="none" stroke="{LINE}" stroke-width="3" stroke-dasharray="16 10"/>',
             text(1020, 222, "back-end (Node.js, TypeScript, Express)", 34, TEAL, 700),
             box(40, 260, 400, 1220, "front-end", ("Android app", "(Kotlin, Compose)", "", "Account and Group UI", "Expense Management")),
             cylinder(90, 1560, 300, 210, "SQLite", ("on the device",)),
             box(cx, comps["bi"], cw, ch, "Bill Import", ("documents, statements, FX quotes",)),
             box(cx, comps["um"], cw, ch, "User Management", ("accounts, sessions, profiles,", "cloud saves")),
             box(cx, comps["gim"], cw, ch, "Group and Invitation\nManagement", ("groups, invitations, membership",)),
             box(cx, comps["gbs"], cw, ch, "Group Bill Splitting", ("expenses, splits, entry sharing,", "split sessions, live events")),
             cylinder(2640, 860, 600, 300, "MongoDB", ("users, sessions, ledgersaves,", "groups, invitations, expenses,", "splitsessions")),
             box(2640, 250, 600, 140, "OpenAI", ("Document Recognition",), "#fff8ea", "#d9b86c"),
             box(2640, 430, 600, 140, "Exchange-rate Service", ("Frankfurter / ECB",), "#fff8ea", "#d9b86c"),
             box(2640, 620, 600, 140, "Google Authentication", ("token verification",), "#fff8ea", "#d9b86c")]

    links = []
    # front-end calls each backend component over HTTPS; Group Bill Splitting also pushes events back.
    for key, rows in (("bi", ["/api/ledger/meta, /exchange,", "/imports/preview, /statement-preview"]),
                      ("um", ["/api/auth/*, /api/users/me,", "/api/ledger-saves/*"]),
                      ("gim", ["/api/groups, /invitations/*,", "/{id}/name, /leave, /members/{id}"])):
        y = comps[key] + ch / 2
        links += [path([(440, y), (cx, y)]), label(715, y - 50, rows)]
    gy = comps["gbs"]
    links += [path([(440, gy + 60), (cx, gy + 60)]),
              label(715, gy + 6, ["/expenses, /shared-entries, /split-sessions,", "/claims, /finalize, /snapshot, /operations"]),
              path([(cx, gy + 128), (440, gy + 128)]),
              label(715, gy + 178, ["onGroupUpdated, onGroupChanged,", "onGroupMembershipChanged (Socket.IO /dutch)"])]
    links += [path([(240, 1480), (240, 1560)]), label(260, 1530, ["records, rates, requests"], "start")]

    # Calls between backend components.
    links += [path([(1200, comps["bi"] + ch), (1200, comps["um"])]),
              label(1220, comps["bi"] + ch + 64, ["requireSession (recognition only)"], "start"),
              path([(1200, comps["gim"]), (1200, comps["um"] + ch)]),
              label(1220, comps["um"] + ch + 64, ["requireSession, user names"], "start"),
              path([(1300, comps["gim"] + ch), (1300, comps["gbs"])]),
              label(1280, comps["gim"] + ch + 64, ["publishMembershipChange"], "end"),
              path([(1560, comps["gbs"]), (1560, comps["gim"] + ch)]),
              label(1580, comps["gim"] + ch + 64, ["group, members, currency"], "start"),
              path([(right, comps["gbs"] + 50), (1800, comps["gbs"] + 50), (1800, comps["um"] + 130), (right, comps["um"] + 130)]),
              label(1820, comps["gim"] - 70, ["requireSession,", "member names"], "start")]

    # Calls to external modules and MongoDB.
    links += [path([(right, comps["bi"] + 60), (2640, 320)]), label(2170, 300, ["extract(file, defaultCurrency)"]),
              path([(right, comps["bi"] + 130), (2500, comps["bi"] + 130), (2500, 500), (2640, 500)]),
              label(2170, comps["bi"] + 116, ["quote(from, to, date)"]),
              path([(right, comps["um"] + 60), (2640, comps["um"] + 60)]), label(2170, comps["um"] + 46, ["verifyGoogleToken"]),
              path([(right, comps["um"] + 100), (2560, comps["um"] + 100), (2560, 900), (2640, 900)]),
              path([(right, comps["gim"] + 90), (2640, comps["gim"] + 90)]),
              path([(right, comps["gbs"] + 120), (2560, comps["gbs"] + 120), (2560, 1120), (2640, 1120)]),
              label(2170, comps["gim"] + 76, ["Mongoose models"])]

    legend = text(40, 1850, "A → B: A calls an interface defined in B.  Front-end ↔ Group Bill Splitting: REST calls one way, "
                  "Socket.IO events the other.  Cylinders are databases; yellow boxes are external modules.", 28, GREY)
    height = 1890
    return page("".join(parts) + "".join(links) + legend, height), height


if __name__ == "__main__":
    out = Path(sys.argv[1])
    for name, build in (("image1", use_case_diagram), ("image2", dependency_diagram)):
        html, height = build()
        (out / f"{name}.html").write_text(html, encoding="utf-8")
        print(name, height)
