#!/usr/bin/env python3
"""Exercise rich chat through the running development client's real UI handlers."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--game-dir', default='run')
args = parser.parse_args()


def ui(action, **values):
    result = subprocess.run([sys.executable, str(ROOT / 'tools/agents.py'), '--game-dir', args.game_dir,
                             'ui', action, '--json', json.dumps(values)], cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(result.stderr or result.stdout)
    return json.loads(result.stdout)


def show(text):
    return ui('dev_chat', rows=[{'id': 'acceptance', 'role': 'assistant', 'text': text}], scroll=0)


def visible_text(view):
    return '\n'.join(row['text'] for row in view['lines'])


ui('dev_window_size', width=1100, height=800)
ui('dev_gui_scale', scale=2)
view = show('# Heading\n\n**bold** *italic* ~~strike~~ `literal *code*`\n\n- [x] done\n- [ ] pending\n\n> quote')
text = visible_text(view)
assert 'Heading' in text and '**bold**' not in text and 'literal *code*' in text
assert '☑' in text and '☐' in text and 'quote' in text
print('PASS: Markdown syntax, literal inline code, task states, quote')

source = '\tfirst  \n\n    second\n**literal**, <tag>, [literal](https://example.com)\n'
view = show('```text\n' + source + '```')
panel = view['panels'][0]
assert panel['source'] == source, panel
ui('click', x=panel['x'] + panel['width'] - 15, y=panel['y'] + 3)
assert ui('dev_chat', clipboardEquals=source)['clipboardMatches']
print('PASS: Code Copy writes exact tabs, trailing spaces, blank lines and markup to the native clipboard')

view = show('```text\n' + '0123456789' * 160 + '\n```')
panel = view['panels'][0]
assert panel['maxScroll'] > 0
ui('scroll', x=panel['x'] + 30, y=panel['y'] + 15, delta=0, horizontal=-8)
assert ui('dev_chat')['panels'][0]['scroll'] > 0
print('PASS: Wide code scrolls horizontally through native wheel handling')

table = '| Left | Center | Right |\n| :--- | :---: | ---: |\n| **bold** | a\\|b | 123 |\n| | `code` | -1 |'
view = show(table)
assert 'Left\tCenter\tRight' in view['panels'][0]['source']
assert 'a|b' in visible_text(view) and '**bold**' not in visible_text(view)
print('PASS: GFM table headers, escaped pipes, empty cells and styled content')

view = show('A **bold** word and a [link](https://example.com).')
row = view['lines'][0]
ui('dev_drag', x=row['x'], y=row['y'] + 3, endX=view['left'] + view['width'] - 20, endY=row['y'] + 3)
assert ui('dev_chat')['selected'] == 'A bold word and a link.'
assert ui('widgets')['screen'] == 'AgentChatScreen', 'Dragging across a link must not open it'
print('PASS: Styled selection and dragging across links')

stream = '# Stream\n\n**bold** [link](https://example.com)\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n```java\n\treturn 1;\n```'
for end in range(1, len(stream) + 1):
    view = show(stream[:end])
    assert view['maxScroll'] >= 0
assert 'return 1;' in visible_text(view)
print(f'PASS: {len(stream)} streamed prefixes, including partial links, table delimiters and code fences')

view = show((ROOT / 'tools/fixtures/rich-chat.md').read_text())
for scale in (3, 2):
    ui('dev_gui_scale', scale=scale)
    resized = ui('dev_chat')
    assert resized['width'] > 0 and resized['bottom'] > resized['top']
ui('screenshot')
print('PASS: Resize/reflow; complete fixture left open for visual inspection')
