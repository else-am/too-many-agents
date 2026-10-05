#!/usr/bin/env python3
"""Exercise rich chat through the running development client's real UI handlers."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--game-dir', default='run')
parser.add_argument('--assets', action='store_true', help='Also test BB-backed images, Mermaid and cleanup; generate fixtures first')
parser.add_argument('--thread-id', help='Existing BB thread whose workspace is this checkout (defaults to current bb thread)')
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

source = '\tfirst  \n\n    second\n**literal**, <tag>, [literal](https://example.com), §a\n'
view = show('```text\n' + source + '```')
panel = view['panels'][0]
assert panel['source'] == source, panel
assert '§a' in visible_text(view), 'Code must not interpret Minecraft color escapes'
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

wide_table = '| ' + ' | '.join(f'Column {i}' for i in range(8)) + ' |\n'
wide_table += '| ' + ' | '.join('---' for _ in range(8)) + ' |\n'
wide_table += '| ' + ' | '.join('Long cell content ' * 4 for _ in range(8)) + ' |'
view = show(wide_table)
panel = view['panels'][0]
assert panel['maxScroll'] > 0
ui('scroll', x=panel['x'] + 30, y=panel['y'] + 15, delta=0, horizontal=-8)
assert ui('dev_chat')['panels'][0]['scroll'] > 0
print('PASS: Wide tables scroll horizontally through native wheel handling')

view = show('A **bold** word, café, 日本語, 😀, and a [link](https://example.com).')
row = view['lines'][0]
ui('dev_drag', x=row['x'], y=row['y'] + 3, endX=view['left'] + view['width'] - 20, endY=row['y'] + 3)
assert ui('dev_chat')['selected'] == 'A bold word, café, 日本語, 😀, and a link.'
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

if args.assets:
    status = json.loads(subprocess.check_output(['bb', 'status', '--json'], cwd=ROOT))
    thread_id = args.thread_id or status['thread']['id']
    ui('dev_chat', threadId=thread_id)

    def click_link(markdown):
        view = show(markdown)
        time.sleep(.4)  # Separate clicks, not Minecraft's double-click word selection.
        line = view['lines'][0]
        return ui('click', x=line['x'] + 5, y=line['y'] + 3)

    for link in ('[Website](https://example.com)', 'https://example.com'):
        assert click_link(link)['screen'] == 'ConfirmLinkScreen'
        assert ui('dev_key', key=256)['screen'] == 'AgentChatScreen'
    for link in ('[Unsafe](javascript:alert%281%29)', '[Command](run_command:/op)'):
        assert click_link(link)['screen'] == 'AgentChatScreen'
        assert 'Unsupported' in ui('dev_chat')['feedback']
    for target, number in (('README.md:13', 13), ('README.md#L14', 14)):
        click_link(f'[Read file]({target})')
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if ui('dev_chat')['feedback'] == 'Opened in BB': break
            time.sleep(.1)
        else: raise AssertionError(ui('dev_chat')['feedback'])
        tabs = json.loads(subprocess.check_output(['bb','thread','tabs','show',thread_id,'--json'],cwd=ROOT))['tabs']
        assert any(tab.get('path') == 'README.md' and tab.get('lineRange',{}).get('startLineNumber') == number for tab in tabs)
    print('PASS: Web/autolink confirmation, safe cancellation, rejected schemes and real BB file:line navigation')

    def settled():
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            view = ui('dev_chat')
            media = view['media']
            if media and all('ready' in m and not m.get('loading', True) for m in media):
                return view
            time.sleep(.15)
        raise AssertionError(('Assets did not settle', view['media']))

    def asset(source, ready=True, error=''):
        show(f'![Acceptance image](<{source}>)')
        view = settled()
        media = view['media'][0]
        assert media['ready'] == ready, media
        if error:
            assert error.lower() in media['error'].lower(), media
        return view

    for extension in ('png', 'jpg', 'gif', 'webp'):
        asset('scratch/rich-chat/colors.' + extension)
    asset('scratch/rich-chat/space in name.png')
    print('PASS: BB workspace PNG, JPEG, GIF, WebP and percent-encoded file paths')

    asset('https://raw.githubusercontent.com/haraldk/TwelveMonkeys/master/logo.png')
    print('PASS: Public HTTPS image loading through the BB plugin')

    upload = json.loads(subprocess.check_output(['bb', 'project', 'attachment', 'upload', status['project']['id'],
        '--client-file', str(ROOT / 'scratch/rich-chat/colors.png'), '--filename', 'rich-chat-acceptance.png', '--json'], cwd=ROOT))
    ui('dev_chat', rows=[{'id': 'attachment', 'role': 'user', 'text': 'Image attachment',
        'attachments': {'localImagePaths': [upload['path']], 'localImages': 1}}], scroll=0)
    assert settled()['media'][0]['ready']
    print('PASS: Real BB-uploaded image through native timeline attachment rendering')

    asset('scratch/rich-chat/too-wide.png', False, '4096')
    asset('scratch/rich-chat/too-many-pixels.png', False, 'megapixels')
    asset('scratch/rich-chat/oversize.png', False, '5 MB')
    asset('scratch/rich-chat/broken.png', False, 'image')
    asset('scratch/rich-chat/missing.png', False)
    asset('http://127.0.0.1/private.png', False, 'private')
    asset('https://example.invalid/missing.png', False)
    asset('javascript:alert(1)', False, 'HTTP')
    print('PASS: Image dimensions, byte limits, malformed data, missing sources and unsafe schemes/hosts')

    retry_path = ROOT / 'scratch/rich-chat/retry.png'
    retry_path.write_text('broken')
    view = asset('scratch/rich-chat/retry.png', False)
    retry_path.write_bytes((ROOT / 'scratch/rich-chat/colors.png').read_bytes())
    media = view['media'][0]
    ui('click', x=media['x'] + 20, y=media['y'] + 20)
    assert settled()['media'][0]['ready']
    print('PASS: Explicit retry loads a repaired image')

    diagrams = {
        'flowchart': 'flowchart LR\n A[BB] --> B[Minecraft]',
        'sequence': 'sequenceDiagram\n BB->>Minecraft: Hello\n Minecraft-->>BB: Read',
        'state': 'stateDiagram-v2\n [*] --> Ready\n Ready --> Working',
        'class': 'classDiagram\n class Message {\n +String text\n +render()\n }',
        'ER': 'erDiagram\n THREAD ||--o{ MESSAGE : contains',
        'XY': 'xychart-beta\n x-axis [Mon, Tue, Wed]\n y-axis "Frames" 0 --> 100\n bar [60,65,70]',
    }
    for name, source in diagrams.items():
        show('```mermaid\n' + source + '\n```')
        assert settled()['media'][0]['ready'], name
    print('PASS: Six Mermaid diagram families through the real BB worker and native texture upload')

    for source in ('pie\n "One" : 1', 'flowchart LR\n A[unfinished', 'sequenceDiagram\n not valid syntax'):
        show('```mermaid\n' + source + '\n```')
        view = settled()
        assert not view['media'][0]['ready'] and view['media'][0]['error']
        assert view['panels'][0]['source'] == source + '\n'
    show('```mermaid\nflowchart LR\n A --> B')
    assert not ui('dev_chat')['media'], 'Do not render unfinished streamed fences'
    print('PASS: Unsupported/malformed diagrams retain copyable source; open fences do not launch workers')

    view = asset('scratch/rich-chat/colors.png')
    media = view['media'][0]
    assert abs(media['width'] / media['height'] - 2) < .02
    assert ui('click', x=media['x'] + media['width'] / 2, y=media['y'] + media['height'] / 2)['screen'] == 'AgentChatScreen'
    print('PASS: Images retain their proportions inline; clicking stays in chat')

    for index in range(28):
        view = asset(f'scratch/rich-chat/cache-{index}.png')
        assert view['imageCacheSize'] <= 24 and view['textureBytes'] <= 64 * 1024 * 1024
    ui('close')
    resources = ui('widgets')['chatImageResources']
    assert resources['textures'] == 0 and resources['bytes'] == 0, resources
    print('PASS: Cache eviction and closing the screen release all native image textures')

    ui('dev_chat',threadId=thread_id)
    show('```mermaid\nflowchart LR\n A[Close while loading] --> B[No leaked textures]\n```')
    ui('close')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        resources = ui('widgets')['chatImageResources']
        assert resources['textures'] == 0 and resources['bytes'] == 0, resources
        if resources['pending'] == 0: break
        time.sleep(.15)
    else: raise AssertionError(('Asset request did not finish after closing', resources))
    print('PASS: Closing during an asynchronous diagram load cannot upload an orphan texture')
