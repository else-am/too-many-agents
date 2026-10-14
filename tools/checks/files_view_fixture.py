"""Real repositories and hostile preview files for files-view acceptance checks.

Git is used only to prepare and independently describe the fixture, before its
hostile configuration is installed. The viewer must never execute those helpers.
"""
from pathlib import Path
import base64
import json
import os
import subprocess
import tempfile


def create(parent):
    folder = Path(tempfile.mkdtemp(prefix='files-view-', dir=parent))
    repo = folder / 'repo'
    repo.mkdir()
    home = folder / 'home'
    home.mkdir()
    environment = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    environment.update(HOME=str(home), GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                       GIT_TERMINAL_PROMPT='0')

    def git(*args, cwd=repo, input=None):
        result = subprocess.run(['git', '-c', 'user.name=Files acceptance', '-c',
                                 'user.email=files-acceptance@example.invalid', *args],
                                cwd=cwd, env=environment, input=input, text=True,
                                check=True, capture_output=True)
        return result.stdout.strip()

    def write(name, contents):
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(contents, bytes):
            path.write_bytes(contents)
        else:
            path.write_text(contents)

    git('init', '--initial-branch=main')
    write('text.txt', ''.join(f'Line {i}\n' for i in range(1, 121)))
    write('cancel.txt', 'original\n')
    write('deleted.txt', 'delete me\n')
    write('move.txt', 'move me\n')
    write('.gitignore', 'build/\n*.log\n')
    write('.gitattributes', 'text.txt filter=fixture diff=fixture\nnormalized.txt text eol=lf\n')
    write('README.md', '''# Preview acceptance

**Bold**, *italic*, `code`, and [a normal link](https://example.com/).

![Safe local image](pixel.png)

<script>document.title='UNSAFE SCRIPT RAN';fetch('https://example.invalid/leak')</script>
<img src="x" onerror="document.title='UNSAFE EVENT RAN'">
[Unsafe script](javascript:alert('unsafe'))
<iframe src="file:///etc/hosts"></iframe>
![Outside image](../secret.png)
''')
    write('source.html', '<!doctype html><script>document.title="UNSAFE HTML RAN"</script>\n')
    write('vector.svg', '<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80">'
          '<rect width="80" height="80" fill="green"/>'
          '<script>document.title="UNSAFE SVG RAN"</script></svg>')
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQ0AAAAASUVORK5CYII=')
    write('pixel.png', png)
    write('binary.dat', bytes(range(256)))
    write('large.txt', b'a' * (2 * 1024 * 1024 + 1))
    write('src/value.ts', 'export const value = 1;\n')
    write('normalized.txt', 'first\nsecond\n')
    write('executable.sh', '#!/bin/sh\necho safe\n')
    write('large-hash.txt', b'x' * (17 * 1024 * 1024))
    (repo / 'tracked-link').symlink_to('text.txt')
    name = "strange & quoted ' name.txt" if os.name == 'nt' else 'strange <img onerror=alert(1)>.txt'
    write(name, 'A filename is text.\n')
    git('add', '.')
    git('commit', '-m', 'base')
    main = git('rev-parse', 'HEAD')
    git('update-ref', 'refs/remotes/origin/main', main)
    git('symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/main')
    git('checkout', '-b', 'feature')
    git('config', 'branch.feature.remote', 'origin')
    git('config', 'branch.feature.merge', 'refs/heads/main')
    write('branch.txt', 'committed on feature\n')
    git('add', 'branch.txt')
    git('commit', '-m', 'branch change')
    head = git('rev-parse', 'HEAD')
    git('worktree', 'add', '-b', 'linked', str(folder / 'linked'))
    # Include packed and loose objects in the same repository.
    git('gc', '--prune=now')
    write('staged.txt', 'new staged file\n')
    write('text.txt', 'staged\n' + ''.join(f'Line {i}\n' for i in range(2, 121)))
    write('cancel.txt', 'temporary staged edit\n')
    git('add', 'text.txt', 'cancel.txt', 'staged.txt')
    write('text.txt', 'working\n' + ''.join(f'Line {i}\n' for i in range(2, 121)))
    write('cancel.txt', 'original\n')
    write('normalized.txt', b'first\r\nsecond\r\n')
    if os.name != 'nt':
        (repo / 'executable.sh').chmod(0o755)
    write('forced.log', 'staged despite ignore\n')
    git('add', '-f', 'forced.log')
    (repo / 'deleted.txt').unlink()
    (repo / 'move.txt').rename(repo / 'moved.txt')
    write('untracked.txt', 'new untracked file\n')
    write('build/ignored.txt', 'visible when expanded\n')
    (folder / 'secret.txt').write_text('OUTSIDE FILE MUST NOT APPEAR\n')
    (folder / 'secret.png').write_bytes(png)
    (repo / 'inside-link').symlink_to('src')
    (repo / 'outside-link').symlink_to('../secret.txt')

    def changed(base):
        tracked = git('diff', '--name-only', '--no-renames', '--no-ext-diff', '--no-textconv', base, '--').splitlines()
        untracked = git('ls-files', '--others', '--exclude-standard').splitlines()
        return sorted(set(tracked + untracked))

    expected = {'branch': changed(main), 'uncommitted': changed(head)}
    assert 'cancel.txt' not in expected['branch']
    assert 'branch.txt' in expected['branch'] and 'branch.txt' not in expected['uncommitted']
    # Use a shell built-in only; the marker records any accidental helper execution.
    marker = folder / 'HELPER-RAN'
    helper = 'echo unsafe >> ' + "'" + str(marker).replace("'", "'\\''") + "'"
    included = repo / '.git' / 'hostile-config'
    included.write_text('[filter "included"]\n\tclean = ' + helper + '\n\trequired = true\n')
    with (repo / '.git' / 'config').open('a') as output:
        output.write('\n[core]\n\tfsmonitor = ' + helper + '\n[filter "fixture"]\n\tclean = ' + helper
                     + '\n\tsmudge = ' + helper + '\n\trequired = true\n[diff "fixture"]\n\tcommand = ' + helper
                     + '\n\ttextconv = ' + helper + '\n[include]\n\tpath = hostile-config\n'
                     '[remote "trap"]\n\turl = ext::' + helper + '\n\tpromisor = true\n')
    (repo / '.git/info/attributes').write_text('text.txt filter=included diff=fixture\n')
    unborn = folder / 'unborn'
    unborn.mkdir()
    git('init', '--initial-branch=main', cwd=unborn)
    (unborn / 'new.txt').write_text('unborn added file\n')
    result = {'folder': str(folder), 'repo': str(repo), 'linked': str(folder / 'linked'),
              'unborn': str(unborn), 'home': str(home), 'marker': str(marker),
              'main': main, 'head': head, 'expected': expected}
    (folder / 'expected.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('parent', type=Path, help='Existing directory for the isolated fixture')
    args = parser.parse_args()
    print(json.dumps(create(args.parent), indent=2))
