from pathlib import Path
import tempfile
import unittest

from retain_web_assets import retain_assets


class RetainAssetsTests(unittest.TestCase):
    def test_preserves_multiple_releases_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for version, name in [('a', 'old.js'), ('b', 'previous.js'), ('c', 'current.js')]:
                assets = root / 'releases' / (version * 40) / 'assets'
                assets.mkdir(parents=True)
                (assets / name).write_text(version)
                (assets / 'shared.js').write_text('shared')
            self.assertEqual(retain_assets(root / 'releases', root / 'retained'), (4, 4))
            self.assertEqual(retain_assets(root / 'releases', root / 'retained'), (4, 0))
            self.assertEqual((root / 'retained/assets/old.js').read_text(), 'a')

    def test_conflicting_release_files_fail_before_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for version in ['a', 'b']:
                assets = root / 'releases' / (version * 40) / 'assets'
                assets.mkdir(parents=True)
                (assets / 'same.js').write_text(version)
            with self.assertRaisesRegex(ValueError, 'Conflicting immutable'):
                retain_assets(root / 'releases', root / 'retained')
            self.assertFalse((root / 'retained').exists())

    def test_never_overwrites_existing_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'releases' / ('a' * 40) / 'assets'
            target = root / 'retained/assets'
            source.mkdir(parents=True)
            target.mkdir(parents=True)
            (source / 'same.js').write_text('new')
            (target / 'same.js').write_text('existing')
            with self.assertRaisesRegex(ValueError, 'Conflicting retained'):
                retain_assets(root / 'releases', root / 'retained')
            self.assertEqual((target / 'same.js').read_text(), 'existing')

    def test_rejects_archive_inside_releases(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, 'separate sibling'):
                retain_assets(root, root / 'retained')


if __name__ == '__main__':
    unittest.main()
