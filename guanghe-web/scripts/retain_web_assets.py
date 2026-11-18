"""Retain immutable assets from every retained release before changing current.

Usage: python3 scripts/retain_web_assets.py /opt/zhixiaohe/web/releases /opt/zhixiaohe/web/retained
Run under the deployment lock. Never delete releases or replace same-name assets.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile

SAFE_RELEASE_NAME = re.compile(r'[a-z0-9]+(?:[-_][a-z0-9]+)*\Z')


def reject_symlinks(path):
    """Reject links in every component, including dangling or inward-pointing links."""
    for component in (path, *path.parents):
        if component.is_symlink():
            raise ValueError(f'Symlinks are not supported: {component}')


def walk_error(error):
    # os.walk otherwise hides unreadable directories and could report false success.
    raise error


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.digest()


def retain_assets(releases, retained, *, dry_run=False, report=False):
    releases, retained = Path(releases), Path(retained)
    reject_symlinks(releases.absolute())
    reject_symlinks(retained.absolute())
    releases, retained = releases.resolve(strict=True), retained.resolve()
    if releases.parent != retained.parent or releases == retained:
        raise ValueError('Retained assets must be a separate sibling of releases')
    if not releases.is_dir() or retained.exists() and not retained.is_dir():
        raise ValueError('Release and retained roots must be directories')
    sources = {}
    inventory = []
    ignored = 0
    for release in sorted(releases.iterdir()):
        # Names are immediate directory entries, never user-supplied relative paths.
        # Retain named release snapshots as well as the historical Git-SHA releases.
        if len(release.name) > 128 or not SAFE_RELEASE_NAME.fullmatch(release.name):
            ignored += 1
            continue
        reject_symlinks(release)
        asset_root = release / 'assets'
        reject_symlinks(asset_root)
        if not release.is_dir() or not asset_root.exists():
            ignored += 1
            continue
        if not asset_root.is_dir():
            raise ValueError(f'Release assets must be a directory: {release.name}')
        count = 0
        for directory, subdirectories, filenames in os.walk(asset_root, followlinks=False, onerror=walk_error):
            subdirectories.sort()
            for name in subdirectories:
                reject_symlinks(Path(directory) / name)
            for name in sorted(filenames):
                source = Path(directory) / name
                reject_symlinks(source)
                if not stat.S_ISREG(source.stat(follow_symlinks=False).st_mode):
                    raise ValueError(f'Asset must be a regular file: {source}')
                if not source.resolve(strict=True).is_relative_to(asset_root):
                    raise ValueError('Release asset path escapes its assets directory')
                relative = source.relative_to(release)
                checksum = digest(source)
                if relative in sources and sources[relative][1] != checksum:
                    raise ValueError(f'Conflicting immutable asset: {relative}')
                destination = retained / relative
                reject_symlinks(destination)
                if not destination.resolve().is_relative_to(retained):
                    raise ValueError('Retained asset path escapes the archive')
                for parent in destination.parents:
                    if parent == retained.parent:
                        break
                    if parent.exists() and not parent.is_dir():
                        raise ValueError(f'Retained asset parent must be a directory: {relative}')
                if destination.exists():
                    if not destination.is_file():
                        raise ValueError(f'Retained asset must be a regular file: {relative}')
                    if digest(destination) != checksum:
                        raise ValueError(f'Conflicting retained asset: {relative}')
                sources[relative] = (source, checksum)
                count += 1
        inventory.append((release.name, count))
    # Validate the complete set before making any changes.
    missing = [(relative, source) for relative, (source, _) in sources.items() if not (retained / relative).exists()]
    if report:
        for name, count in inventory:
            print(f'RELEASE name={name} assets={count}')
        print(f'INVENTORY releases={len(inventory)} ignored={ignored} assets={len(sources)} missing={len(missing)}')
        if dry_run:
            for relative, source in missing:
                print(f'WOULD_RETAIN asset={relative.as_posix()} source={source.relative_to(releases).parts[0]}')
    if dry_run:
        return len(sources), 0
    copied = 0
    for relative, (source, expected_checksum) in sources.items():
        destination = retained / relative
        reject_symlinks(destination)
        if destination.exists():
            if digest(destination) != expected_checksum:
                raise ValueError(f'Retained asset changed after validation: {relative}')
            continue
        reject_symlinks(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        reject_symlinks(destination.parent)
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.retain-', delete=False) as stream:
            temporary = Path(stream.name)
        try:
            shutil.copyfile(source, temporary, follow_symlinks=False)
            if digest(temporary) != expected_checksum:
                raise ValueError(f'Release asset changed after validation: {relative}')
            temporary.chmod(0o644)
            # Publish atomically without ever replacing an existing file.
            os.link(temporary, destination)
            copied += 1
        finally:
            temporary.unlink()
    return len(sources), copied


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('releases')
    parser.add_argument('retained')
    parser.add_argument('--dry-run', action='store_true', help='Read and validate every release/asset; print missing assets without creating or copying files')
    args = parser.parse_args()
    total, copied = retain_assets(args.releases, args.retained, dry_run=args.dry_run, report=True)
    print(f'{"RETAINED_ASSETS_DRY_RUN_OK" if args.dry_run else "RETAINED_ASSETS_OK"} total={total} copied={copied}')
