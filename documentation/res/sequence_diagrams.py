"""Render the two-panel UC-03 to UC-05 sequence diagrams (Figures 5-7) as HTML pages.

Usage: python sequence_diagrams.py sequence_diagrams.json <output-dir>
Each page is 3400 px wide; screenshot it at the printed height (for example with a
headless browser) to produce the PNG embedded in Requirements_and_Design.md.
"""
import json
import sys
from pathlib import Path
from xml.sax.saxutils import escape

W = 3400
MARGIN = 24
GAP = 44
PW = (W - 2 * MARGIN - GAP) // 2
TEAL, INK, GREY, LINE, FILL = "#0f7f86", "#1e2a3a", "#5d6b78", "#a9b6c2", "#eef3f6"
FONT = 37
ROW = 76
FRAME_HEAD = 46


def text_width(s, size=FONT):
    return len(s) * size * 0.5


class Panel:
    def __init__(self, x, heading, actors):
        self.x, self.heading, self.actors = x, heading, actors
        step = (PW - 240) / (len(actors) - 1)
        self.pos = {a: x + 120 + i * step for i, a in enumerate(actors)}
        self.out = []

    def label(self, s, cx, y, color, anchor="middle"):
        w = text_width(s)
        if anchor == "middle":
            left = min(max(cx - w / 2, self.x + 8), self.x + PW - 8 - w)
        else:
            left = min(max(cx, self.x + 8), self.x + PW - 8 - w)
        self.out.append(f'<text x="{left:.0f}" y="{y:.0f}" fill="{color}" font-size="{FONT}">{escape(s)}</text>')

    def render(self, items, y):
        for item in items:
            kind = item[0]
            if kind in ("msg", "ret"):
                _, a, b, s = item
                xa, xb = self.pos[a], self.pos[b]
                color = TEAL if kind == "msg" else GREY
                self.label(s, (xa + xb) / 2, y + 32, INK if kind == "msg" else GREY)
                dash = '' if kind == "msg" else ' stroke-dasharray="9 7"'
                marker = "url(#solid)" if kind == "msg" else "url(#open)"
                self.out.append(f'<line x1="{xa:.0f}" y1="{y + 48}" x2="{xb:.0f}" y2="{y + 48}" stroke="{color}" '
                                f'stroke-width="3"{dash} marker-end="{marker}"/>')
                y += ROW
            elif kind == "self":
                _, a, s = item
                xa = self.pos[a]
                self.label(s, xa - 52, y + 30, INK, anchor="start")
                self.out.append(f'<path d="M{xa:.0f},{y + 40} h56 v16 h-50" fill="none" stroke="{TEAL}" '
                                f'stroke-width="3" marker-end="url(#solid)"/>')
                y += ROW
            else:  # frame: ("frame", "ALT New source", [items])
                _, title, inner = item
                top = y
                y = self.render(inner, y + FRAME_HEAD)
                bottom = y + 10
                self.out.insert(0, f'<rect x="{self.x}" y="{top}" width="{PW}" height="{bottom - top}" fill="none" '
                                   f'stroke="{LINE}" stroke-width="2"/>'
                                   f'<rect x="{self.x + 1}" y="{top + 1}" width="{PW - 2}" height="{FRAME_HEAD - 6}" fill="{FILL}"/>'
                                   f'<text x="{self.x + 14}" y="{top + 31}" fill="{TEAL}" font-size="30" font-weight="700">{escape(title)}</text>')
                y = bottom + 8
        return y


def build(spec):
    panels = [Panel(MARGIN, spec["a"]["heading"], spec["actors"]),
              Panel(MARGIN + PW + GAP, spec["b"]["heading"], spec["actors"])]
    start = 250
    ends = [p.render(part["items"], start) for p, part in zip(panels, (spec["a"], spec["b"]))]
    bottom = max(ends) + 20
    parts = [f'<rect width="{W}" height="{bottom + 30}" fill="#ffffff"/>',
             f'<text x="{MARGIN}" y="66" fill="{INK}" font-size="58" font-weight="700">{escape(spec["title"])}</text>',
             f'<text x="{W - MARGIN}" y="62" fill="{TEAL}" font-size="44" font-weight="700" text-anchor="end">{escape(spec["tag"])}</text>',
             f'<line x1="{MARGIN}" y1="92" x2="{W - MARGIN}" y2="92" stroke="{TEAL}" stroke-width="3"/>',
             f'<line x1="{W / 2}" y1="120" x2="{W / 2}" y2="{bottom}" stroke="#d5dde4" stroke-width="3"/>']
    for p in panels:
        parts.append(f'<text x="{p.x}" y="140" fill="{TEAL}" font-size="44" font-weight="700">{escape(p.heading)}</text>')
        for a, x in p.pos.items():
            parts.append(f'<line x1="{x:.0f}" y1="214" x2="{x:.0f}" y2="{bottom}" stroke="{LINE}" stroke-width="2.5" stroke-dasharray="12 9"/>')
            bw = max(text_width(a, 36) + 40, 200)
            parts.append(f'<rect x="{x - bw / 2:.0f}" y="160" width="{bw:.0f}" height="54" rx="10" fill="{FILL}" stroke="#c4d0da" stroke-width="2"/>'
                         f'<text x="{x:.0f}" y="199" fill="{INK}" font-size="36" font-weight="700" text-anchor="middle">{escape(a)}</text>')
    body = "".join(parts) + "".join(panels[0].out) + "".join(panels[1].out)
    defs = (f'<defs><marker id="solid" markerWidth="14" markerHeight="14" refX="12" refY="5" orient="auto" markerUnits="userSpaceOnUse">'
            f'<path d="M0,0 L12,5 L0,10 z" fill="{TEAL}"/></marker>'
            f'<marker id="open" markerWidth="14" markerHeight="14" refX="12" refY="5" orient="auto" markerUnits="userSpaceOnUse">'
            f'<path d="M0,0 L12,5 L0,10" fill="none" stroke="{GREY}" stroke-width="2"/></marker></defs>')
    height = bottom + 30
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{height}" viewBox="0 0 {W} {height}" '
           f'font-family="Arial, Helvetica, sans-serif">{defs}{body}</svg>')
    return svg, height


if __name__ == "__main__":
    out = Path(sys.argv[2])
    for name, spec in json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")).items():
        svg, height = build(spec)
        (out / f"{name}.html").write_text(
            f'<!doctype html><html><head><meta charset="utf-8"><style>html,body{{margin:0;padding:0;background:#fff}}</style></head>'
            f'<body>{svg}</body></html>', encoding="utf-8")
        print(name, height)
