#!/usr/bin/env python3
"""Render production PanelView offline, then compose/encode the public introduction.

Python 3 + Pillow; JDK 17, Android SDK 35, Gradle/Robolectric, ffmpeg and Noto fonts.
No Activity, app settings, API endpoint, credentials, or network data are used.
"""
import argparse
from contextlib import contextmanager
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
EDIT = ROOT / 'docs/intro-video/edit.json'
FONTS = Path('/usr/share/fonts')


def load_edit(path=EDIT):
    edit = json.loads(Path(path).read_text())
    assert (edit['width'], edit['height'], edit['fps']) == (1920, 1080, 30)
    for version, scenes in edit['versions'].items():
        assert sum(round(seconds * edit['fps']) for _, seconds in scenes) == int(version) * edit['fps']
        assert all(name in edit['scenes'] and seconds > 0 for name, seconds in scenes)
    return edit


def run(command, *, env=None):
    """Interrupt the whole child group, including Gradle's test JVM, and reap it."""
    process = subprocess.Popen(command, cwd=ROOT, env=env, start_new_session=True)
    try:
        if process.wait() != 0:
            raise RuntimeError(f'Command failed: {command[0]} (exit {process.returncode})')
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


@contextmanager
def atomic_destination(destination, overwrite):
    """Only publish a fully verified file; never truncate an existing finished one."""
    destination = Path(destination).absolute()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and not overwrite:
        raise FileExistsError(f'{destination} exists; pass --overwrite explicitly')
    fd, name = tempfile.mkstemp(prefix=f'.{destination.stem}-', suffix=destination.suffix, dir=destination.parent)
    os.close(fd)
    temporary = Path(name)
    try:
        yield temporary
        if overwrite:
            os.replace(temporary, destination)
        else:
            # Atomic no-clobber even if another process publishes after the initial check.
            os.link(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def font(size, bold=False, mono=False):
    path = FONTS / ('truetype/noto/NotoSansMono-' + ('Bold' if bold else 'Regular') + '.ttf' if mono else
                    'opentype/noto/NotoSansCJK-' + ('Bold' if bold else 'Regular') + '.ttc')
    return ImageFont.truetype(str(path), size, index=0)


def background():
    image = Image.new('RGB', (1920, 1080), '#07131c')
    # A quiet teal light in the upper right, computed once; no external assets.
    glow = Image.new('L', (192, 108))
    glow.putdata([round(40 * math.exp(-((x - 171) / 100) ** 2 - ((y + 10) / 80) ** 2))
                  for y in range(108) for x in range(192)])
    glow = glow.resize(image.size, Image.Resampling.BICUBIC)
    image.paste(Image.new('RGB', image.size, '#1c6563'), mask=glow)
    return image


def compose_frames(frames, edit, version):
    bg = background()
    white, mint, muted, low = '#e7f0f4', '#72e2c5', '#8fa8b5', '#5f7886'
    labels = edit['labels']
    index = 0
    for chapter, (name, seconds) in enumerate(edit['versions'][version]):
        count = round(seconds * edit['fps'])
        scene = edit['scenes'][name]
        base = bg.copy()
        draw = ImageDraw.Draw(base)
        def text(x, y, value, size, color=white, bold=False, mono=False):
            draw.text((x, y), value, font=font(size, bold, mono), fill=color)
        draw.line((80, 92, 1840, 92), '#1d3341', 2)
        text(80, 35, labels['brand'], 24, mint, mono=True)
        text(1440, 35, labels['demo'], 25, muted)
        if name == 'intro':
            text(80, 225, edit['product'], 57, bold=True)
            for row, line in enumerate(edit['catch']):
                text(80, 330 + row * 82, line, 44, mint if row else white, True)
            text(82, 560, labels['platform'], 22, muted, mono=True)
            text(82, 624, labels['intro_detail'], 30, muted)
            panel_box = (880, 350, 1840, 830)
        elif name == 'outro':
            text(150, 230, edit['product'], 86, bold=True)
            text(154, 375, ''.join(edit['catch']), 48, mint, True)
            draw.rounded_rectangle((150, 540, 1770, 660), radius=14, fill='#102e34', outline='#1d3341', width=2)
            text(190, 566, edit['lp'], 42, mint, mono=True)
            text(155, 718, edit['github'], 30, muted, mono=True)
            text(155, 850, scene['note'], 27, muted)
            panel_box = None
        else:
            text(80, 111, scene['heading'], 51, bold=True)
            text(1690, 128, f'{chapter:02d}', 30, low, mono=True)
            panel_box = (288, 210, 1632, 882)  # 1344×672, exactly 2:1
        if panel_box:
            x, y, right, bottom = panel_box
            draw.rounded_rectangle((x - 10, y - 10, right + 10, bottom + 10), radius=16,
                                   fill='#0b1822', outline='#1d3341', width=2)
            text(80, 936, scene['note'], 29, muted)
        text(80, 1024, labels['footer'], 18, low, mono=True)
        text(1600, 1020, labels['silent'], 22, muted)
        for local in range(count):
            image = base.copy()
            if panel_box:
                with Image.open(frames / f'{index:05d}.png') as panel:
                    image.paste(panel.convert('RGB').resize((right - x, bottom - y), Image.Resampling.LANCZOS), (x, y))
            progress = ImageDraw.Draw(image)
            progress.line((80, 1005, 1840, 1005), '#1d3341', 2)
            progress.line((80, 1005, 80 + round(1760 * (local + 1) / count), 1005), mint, 3)
            # Six frames in/out, with no timestamp or random input.
            alpha = min(1, (local + 1) / 6, (count - local) / 6)
            if alpha < 1:
                image = Image.blend(bg, image, alpha)
            yield image
            index += 1


def encode(images, output, *, ffmpeg='ffmpeg', fps=30):
    command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo',
               '-pixel_format', 'rgb24', '-video_size', '1920x1080', '-framerate', str(fps),
               '-i', 'pipe:0', '-an', '-vf', 'scale=out_color_matrix=bt709:out_range=tv',
               '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-threads', '1',
               '-pix_fmt', 'yuv420p', '-colorspace', 'bt709', '-color_primaries', 'bt709',
               '-color_trc', 'bt709', '-color_range', 'tv',
               '-x264-params', 'colorprim=bt709:transfer=bt709:colormatrix=bt709', '-movflags', '+faststart',
               '-fflags', '+bitexact', '-flags:v', '+bitexact', '-map_metadata', '-1', str(output)]
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=errors)
        try:
            try:
                for image in images:
                    process.stdin.write(image.tobytes())
                process.stdin.close()
            except BrokenPipeError:
                pass
            if process.wait() != 0:
                errors.seek(0)
                raise RuntimeError('ffmpeg failed: ' + errors.read().decode(errors='replace')[-4000:])
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
            if process.stdin and not process.stdin.closed:
                process.stdin.close()


def verify(path, seconds):
    data = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_format',
                                               '-of', 'json', str(path)]))
    streams = data['streams']
    assert len(streams) == 1, 'Expected video only'
    video = streams[0]
    expected = {'codec_name': 'h264', 'width': 1920, 'height': 1080, 'pix_fmt': 'yuv420p',
                'r_frame_rate': '30/1', 'nb_frames': str(seconds * 30), 'color_space': 'bt709',
                'color_transfer': 'bt709', 'color_primaries': 'bt709', 'color_range': 'tv'}
    for key, value in expected.items():
        assert video.get(key) == value, (key, video.get(key), value)
    assert abs(float(data['format']['duration']) - seconds) < .001
    # faststart: the movie metadata precedes the media data.
    payload = Path(path).read_bytes()
    assert payload.index(b'moov') < payload.index(b'mdat')
    return video


def make_poster(video, destination, overwrite=False, ffmpeg='ffmpeg'):
    video = Path(video).absolute()
    with atomic_destination(destination, overwrite) as temporary:
        run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-ss', '1.8', '-i', str(video),
             '-frames:v', '1', '-vf', 'scale=1280:720', '-q:v', '4', '-threads', '1', str(temporary)])
        with Image.open(temporary) as image:
            assert image.format == 'JPEG' and image.size == (1280, 720)
    print(f'Poster: {destination}', flush=True)


def generate(version, destination, overwrite, edit_path, ffmpeg):
    edit = load_edit(edit_path)
    with atomic_destination(destination, overwrite) as temporary:
        with tempfile.TemporaryDirectory(prefix='panel-intro-') as work:
            frames = Path(work) / 'frames'
            env = os.environ.copy()
            env.update(PANEL_VIDEO_FRAMES=str(frames), PANEL_VIDEO_VERSION=version,
                       PANEL_VIDEO_EDIT=str(Path(edit_path).resolve()), TZ=edit['timezone'])
            print(f'Rendering {version}s: production PanelView → {frames}', flush=True)
            run([str(ROOT / 'gradlew'), ':app:testDebugUnitTest', '--tests',
                 'io.github.ysogabe.miharashi.panel.IntroVideoTest.renderFrames', '--no-daemon'], env=env)
            print(f'Encoding {version}s → {destination}', flush=True)
            encode(compose_frames(frames, edit, version), temporary, ffmpeg=ffmpeg)
            verify(temporary, int(version))
    print(f'Finished: {destination} ({Path(destination).stat().st_size:,} bytes)', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=['10', '30', '60', 'all'], default='30')
    parser.add_argument('--out-dir', type=Path, default=ROOT / 'work/intro')
    parser.add_argument('--overwrite', action='store_true')
    parser.add_argument('--poster', action='store_true', help='Also extract the 30s poster at 1.8s')
    parser.add_argument('--edit', type=Path, default=EDIT)
    parser.add_argument('--ffmpeg', default='ffmpeg', help='Encoder executable (also useful for failure checks)')
    args = parser.parse_args()
    def interrupted(signum, _frame):
        raise KeyboardInterrupt(f'Interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        for version in ['10', '30', '60'] if args.version == 'all' else [args.version]:
            generate(version, args.out_dir / f'miharashi-panel-{version}s.mp4', args.overwrite, args.edit, args.ffmpeg)
            if args.poster and version == '30':
                make_poster(args.out_dir / 'miharashi-panel-30s.mp4',
                            args.out_dir / 'miharashi-panel-30s-poster.jpg', args.overwrite, args.ffmpeg)
    except (Exception, KeyboardInterrupt) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 130 if isinstance(exc, KeyboardInterrupt) else 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
