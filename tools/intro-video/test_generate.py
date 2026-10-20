"""Failure/interrupt/overwrite checks run without an SDK or network."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

spec = importlib.util.spec_from_file_location('generate', Path(__file__).with_name('generate.py'))
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)


class OutputSafetyTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.directory = Path(self.work.name)
        self.output = self.directory / 'nested/video.mp4'

    def assert_clean(self):
        self.assertEqual([], list(self.output.parent.glob('.*.mp4')))

    def test_existing_output_requires_explicit_overwrite(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b'finished')
        with self.assertRaisesRegex(FileExistsError, '--overwrite'):
            with g.atomic_destination(self.output, False):
                self.fail('Must reject before starting a renderer')
        self.assertEqual(b'finished', self.output.read_bytes())
        self.assert_clean()

    def test_encoder_failure_preserves_old_file_and_cleans_temporary(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b'finished')
        with self.assertRaisesRegex(RuntimeError, 'ffmpeg failed'):
            with g.atomic_destination(self.output, True) as temporary:
                g.encode([Image.new('RGB', (1920, 1080))], temporary, ffmpeg='/usr/bin/false')
        self.assertEqual(b'finished', self.output.read_bytes())
        self.assert_clean()

    def test_failure_without_existing_output_leaves_no_completed_file(self):
        with self.assertRaises(FileNotFoundError):
            with g.atomic_destination(self.output, False) as temporary:
                g.encode([], temporary, ffmpeg=str(self.directory / 'missing-encoder'))
        self.assertFalse(self.output.exists())
        self.assert_clean()

    def test_interrupt_during_real_encoding_reaps_encoder_and_preserves_finished_file(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b'finished')
        def frames():
            yield Image.new('RGB', (1920, 1080))
            raise KeyboardInterrupt()
        created = []
        popen = subprocess.Popen
        def record(*args, **kwargs):
            process = popen(*args, **kwargs)
            created.append(process)
            return process
        with patch.object(g.subprocess, 'Popen', side_effect=record):
            with self.assertRaises(KeyboardInterrupt):
                with g.atomic_destination(self.output, True) as temporary:
                    g.encode(frames(), temporary)
        self.assertTrue(created)
        self.assertIsNotNone(created[0].poll())
        self.assertEqual(b'finished', self.output.read_bytes())
        self.assert_clean()

    def test_success_and_atomic_no_clobber_race(self):
        with g.atomic_destination(self.output, False) as temporary:
            temporary.write_bytes(b'complete')
        self.assertEqual(b'complete', self.output.read_bytes())
        with self.assertRaises(FileExistsError):
            with g.atomic_destination(self.output, False):
                pass
        self.output.unlink()
        with self.assertRaises(FileExistsError):
            with g.atomic_destination(self.output, False) as temporary:
                temporary.write_bytes(b'loser')
                self.output.write_bytes(b'other-writer')
        self.assertEqual(b'other-writer', self.output.read_bytes())
        with g.atomic_destination(self.output, True) as temporary:
            temporary.write_bytes(b'replacement')
        self.assertEqual(b'replacement', self.output.read_bytes())
        self.assert_clean()

    def test_fixed_images_encode_to_identical_bytes(self):
        image = Image.new('RGB', (1920, 1080), '#07131c')
        first, second = self.directory / 'first.mp4', self.directory / 'second.mp4'
        g.encode([image, image, image], first)
        g.encode([image, image, image], second)
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_failed_poster_keeps_existing_poster(self):
        poster = self.directory / 'poster.jpg'
        poster.write_bytes(b'finished-poster')
        with self.assertRaisesRegex(RuntimeError, 'Command failed'):
            g.make_poster(self.directory / 'missing.mp4', poster, True)
        self.assertEqual(b'finished-poster', poster.read_bytes())
        self.assertEqual([], list(self.directory.glob('.*.jpg')))

    def test_editorial_frame_counts_and_proportions(self):
        edit = g.load_edit()
        self.assertEqual([300, 900, 1800], [sum(round(s * 30) for _, s in edit['versions'][v])
                                          for v in ('10', '30', '60')])
        self.assertEqual(['intro', 'selection', 'outro'], [n for n, _ in edit['versions']['10']])
        self.assertEqual(2, 1344 / 672)


if __name__ == '__main__':
    unittest.main()
