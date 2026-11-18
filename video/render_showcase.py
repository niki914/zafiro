import os, sys, time, json, subprocess
from PIL import Image, ImageDraw, ImageFont, ImageChops, ImageFilter

print("=== Starting Zafiro Showcase 4:3 Video Rendering ===")
print("Architecture: Exact Floodfill Screen Mask + Balanced Equal-Margins (140:466:140:554:140)")
t_start = time.time()

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# Load configuration from video/config.json (or custom path from sys.argv)
config_path = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].endswith('.json') else os.path.join(BASE_DIR, 'config.json')
config = {}
if os.path.exists(config_path):
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
        print(f"Loaded config from: {config_path}")
    except Exception as e:
        print(f"Warning: Failed to parse {config_path} ({e}), falling back to defaults.")
else:
    print(f"Config file not found at {config_path}, using default settings.")

def resolve_resolution(cfg):
    w = cfg.get('width')
    h = cfg.get('height')
    res = cfg.get('resolution')
    if w is not None and h is not None:
        cw, ch = int(w), int(h)
    elif res is not None:
        if isinstance(res, int):
            ch = res
            cw = int(round(ch * 4.0 / 3.0))
        elif isinstance(res, str):
            res_str = res.strip().lower()
            if 'x' in res_str:
                parts = res_str.split('x')
                cw, ch = int(parts[0]), int(parts[1])
            elif res_str.endswith('p'):
                ch = int(res_str[:-1])
                cw = int(round(ch * 4.0 / 3.0))
            else:
                ch = int(res_str)
                cw = int(round(ch * 4.0 / 3.0))
        elif isinstance(res, (list, tuple)) and len(res) == 2:
            cw, ch = int(res[0]), int(res[1])
        elif isinstance(res, dict):
            ch = int(res.get('height', 720))
            cw = int(res.get('width', round(ch * 4.0 / 3.0)))
        else:
            cw, ch = 960, 720
    else:
        cw, ch = 960, 720

    # Ensure even dimensions for H.264 / yuv420p
    cw = cw - (cw % 2)
    ch = ch - (ch % 2)
    return cw, ch

def get_video_encoder():
    try:
        res = subprocess.run(['ffmpeg', '-encoders'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        if 'libx264' in res.stdout:
            return 'libx264'
        if 'h264_videotoolbox' in res.stdout:
            return 'h264_videotoolbox'
    except Exception:
        pass
    return 'libx264'

FPS = int(config.get('fps', 12))
CW, CH = resolve_resolution(config)

BASE_W, BASE_H = 1440, 1080
scale = CH / float(BASE_H)

encoder = config.get('encoder') or get_video_encoder()
crf = config.get('crf', 22)
preset = config.get('preset', 'slow')
bitrate = config.get('bitrate')

if encoder in ('libx264', 'libx265'):
    if bitrate is not None:
        b_str = f"{bitrate}k" if isinstance(bitrate, int) else str(bitrate)
        enc_params = ['-c:v', encoder, '-preset', preset, '-b:v', b_str]
        rate_desc = f"Bitrate = {b_str}"
    else:
        enc_params = ['-c:v', encoder, '-preset', preset, '-crf', str(crf)]
        rate_desc = f"CRF = {crf} (Preset: {preset})"
elif encoder == 'libsvtav1':
    av1_p = str(config.get('av1_preset', 6))
    enc_params = ['-c:v', 'libsvtav1', '-preset', av1_p, '-crf', str(config.get('crf', 28))]
    rate_desc = f"CRF = {config.get('crf', 28)} (SVT-AV1 Preset: {av1_p})"
elif encoder == 'h264_videotoolbox':
    default_bitrate = "4000k" if CH >= 1080 else ("3000k" if CH >= 720 else "2000k")
    b = bitrate if bitrate is not None else default_bitrate
    b_str = f"{b}k" if isinstance(b, int) else str(b)
    enc_params = ['-c:v', encoder, '-b:v', b_str]
    rate_desc = f"Bitrate = {b_str} (videotoolbox)"
else:
    if bitrate is not None:
        b_str = f"{bitrate}k" if isinstance(bitrate, int) else str(bitrate)
        enc_params = ['-c:v', encoder, '-b:v', b_str]
        rate_desc = f"Bitrate = {b_str}"
    else:
        enc_params = ['-c:v', encoder, '-crf', str(crf)]
        rate_desc = f"CRF = {crf}"

print(f"Video Configuration: Resolution = {CW}x{CH} (4:3), Framerate = {FPS} fps, Encoder = {encoder}, {rate_desc}, Scale = {scale:.4f}")

# Balanced Equal-Margins Grid (Proportionally scaled to target resolution):
# Base [Margin 140px] [Phone 466px] [Gap 140px] [Text 554px] [Margin 140px] = 1440px
PH_W = int(round(466 * scale))
PH_H = int(round(960 * scale))
PH_X = int(round(140 * scale))
PH_Y = (CH - PH_H) // 2

# Video layer size and placement (covers full screen hole + underlap)
REL_VID_X = int(round(20 * scale))
REL_VID_Y = int(round(18 * scale))
SC_VID_W = int(round(426 * scale))
SC_VID_H = int(round(924 * scale))
SC_VID_W = SC_VID_W - (SC_VID_W % 2)
SC_VID_H = SC_VID_H - (SC_VID_H % 2)
SC_VID_X = PH_X + REL_VID_X
SC_VID_Y = PH_Y + REL_VID_Y

# Typography layout (Google Sans Flex)
text_x = int(round(746 * scale))
text_y = int(round(475 * scale))
sub_offset_y = int(round(98 * scale))
slide_dy_max = int(round(45 * scale))

FONT_BOLD = os.path.join(BASE_DIR, 'res', 'fonts', 'GoogleSansFlex-Bold900.ttf')
FONT_REG = os.path.join(BASE_DIR, 'res', 'fonts', 'GoogleSansFlex-Regular400.ttf')

font_title = ImageFont.truetype(FONT_BOLD, max(12, int(round(76 * scale))))
font_sub = ImageFont.truetype(FONT_REG, max(8, int(round(28 * scale))))

# 1. Background Preparation with 20% Black Overlay (100% Solid Opaque, Alpha=255)
raw_bg = Image.open(os.path.join(BASE_DIR, 'res', 'background.jpeg')).convert('RGB')
CROP_Y_BASE = int(round(40 * scale))
PARALLAX_STEP = int(round(20 * scale))
BG_W = max(int(round(1600 * scale)), CW + 40)
BG_H = max(int(round(1195 * scale)), CH + CROP_Y_BASE + 4 * PARALLAX_STEP + 20)
bg_scaled = raw_bg.resize((BG_W, BG_H), Image.Resampling.LANCZOS)
black_img = Image.new('RGB', (BG_W, BG_H), (0, 0, 0))
bg_dimmed = Image.blend(bg_scaled, black_img, 0.20)

CROP_X = (BG_W - CW) // 2

def get_base_bg(y_shift):
    y = int(round(CROP_Y_BASE + y_shift))
    return bg_dimmed.crop((CROP_X, y, CROP_X + CW, y + CH)).convert('RGBA')

# 2. Prepare Phone Mockup & Extract Exact Interior Screen Masks
phone_mockup = Image.open(os.path.join(BASE_DIR, 'res', 'phone_mockup.png')).convert('RGBA').resize((PH_W, PH_H), Image.Resampling.LANCZOS)

def build_screen_masks(mockup_rgba, s):
    """
    Floodfills from the center of phone_mockup to extract the exact rounded screen cutout.
    - screen_hole_mask: exact interior screen cutout (255 inside screen, 0 on bezel & outside)
    - screen_underlap_mask: expanded 1~2px into the opaque black bezel so video never leaves
      a hairline gap, while staying strictly inside the outer chassis (zero corner/bottom leak).
    """
    w, h = mockup_rgba.size
    alpha = mockup_rgba.split()[3]
    non_opaque = alpha.point(lambda p: 255 if p < 250 else 0)
    ff = non_opaque.copy()
    ImageDraw.floodfill(ff, (w // 2, h // 2), 128)
    screen_hole_mask = ff.point(lambda p: 255 if p == 128 else 0)
    filter_size = max(3, int(round(5 * s)))
    if filter_size % 2 == 0:
        filter_size += 1
    screen_underlap_mask = screen_hole_mask.filter(ImageFilter.MaxFilter(filter_size))
    return screen_hole_mask, screen_underlap_mask

screen_hole_mask, screen_underlap_mask = build_screen_masks(phone_mockup, scale)

SCENES = [
    {
        'id': '01',
        'title': 'Phone Use',
        'sub': 'Virtual screen background execution',
        'video': os.path.join(BASE_DIR, 'res', 'zafiro__phone_use_visual_screen.mp4')
    },
    {
        'id': '02',
        'title': 'Coding',
        'sub': 'Instant HTML generation',
        'video': os.path.join(BASE_DIR, 'res', 'zafiro__html_creation.mp4')
    },
    {
        'id': '03',
        'title': 'Phone Use',
        'sub': 'On-screen app operations',
        'video': os.path.join(BASE_DIR, 'res', 'zafiro__phone_use_deepseek.mp4')
    },
    {
        'id': '04',
        'title': 'Text Actions',
        'sub': 'Contextual actions from selected text',
        'video': os.path.join(BASE_DIR, 'res', 'zafiro__text_actions.mp4')
    }
]

# Probe durations
for sc in SCENES:
    probe = subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries', 'format=duration',
        '-of', 'json', sc['video']
    ]).decode()
    sc['dur'] = float(json.loads(probe)['format']['duration'])
    print(f"Scene {sc['id']} ({sc['title']}): {sc['dur']:.2f}s")

tmp_dir = f'/tmp/zafiro_render_{CW}x{CH}_{FPS}fps'
os.makedirs(tmp_dir, exist_ok=True)

# 3. Create Master Matte Keyhole Overlay (Zero bg bleed, strictly sealed bottom & corners)
def create_scene_overlay(scene_idx):
    sc = SCENES[scene_idx]
    y_shift = scene_idx * PARALLAX_STEP
    overlay = get_base_bg(y_shift)
    draw = ImageDraw.Draw(overlay)

    # Clean Typography (Balanced grid)
    draw.text((text_x, text_y), sc['title'], font=font_title, fill=(255, 255, 255, 255))
    draw.text((text_x, text_y + sub_offset_y), sc['sub'], font=font_sub, fill=(255, 255, 255, 225))

    # Punch out exact screen keyhole in background alpha first so blue bg never bleeds into inner bezel AA
    bg_alpha = Image.new('L', (CW, CH), 255)
    bg_alpha.paste(ImageChops.invert(screen_hole_mask), (PH_X, PH_Y))
    overlay.putalpha(bg_alpha)

    # Composite phone mockup cleanly over the punched keyhole
    mockup_layer = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
    mockup_layer.paste(phone_mockup, (PH_X, PH_Y))
    return Image.alpha_composite(overlay, mockup_layer)

# 4. Render Segments using Master Matte Overlay
seg_files = []
for i, sc in enumerate(SCENES):
    ov_img = create_scene_overlay(i)
    ov_path = f"{tmp_dir}/overlay_scene_{i}.png"
    ov_img.save(ov_path)

    seg_mp4 = f"{tmp_dir}/seg_{i}.mp4"
    print(f"Rendering Segment {i+1}/4 ({sc['title']} - {sc['dur']:.2f}s) with Master Matte Overlay...")

    cmd = [
        'ffmpeg', '-y',
        '-f', 'lavfi', '-i', f'color=c=black:s={CW}x{CH}:r={FPS}',
        '-i', sc['video'],
        '-loop', '1', '-t', str(sc['dur']), '-i', ov_path,
        '-filter_complex',
        f'[1:v]fps={FPS},scale={SC_VID_W}:{SC_VID_H}:flags=lanczos[screen];'
        f'[0:v][screen]overlay={SC_VID_X}:{SC_VID_Y}:shortest=1[base_with_vid];'
        f'[base_with_vid][2:v]overlay=0:0:format=rgb,format=yuv420p[out]',
        '-map', '[out]',
        '-an',
    ] + enc_params + [
        '-pix_fmt', 'yuv420p',
        '-r', str(FPS),
        '-t', str(sc['dur']),
        seg_mp4
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    seg_files.append(seg_mp4)

# 5. Generate Transition Units with Exact Screen Underlap Clipping
def make_phone_unit(screen_img):
    screen_layer = Image.new('RGBA', (PH_W, PH_H), (0, 0, 0, 0))
    screen_layer.paste(screen_img.convert('RGBA'), (REL_VID_X, REL_VID_Y))

    # Clip screen strictly to interior screen + underlap
    screen_layer.putalpha(screen_underlap_mask)

    return Image.alpha_composite(screen_layer, phone_mockup)

trans_files = []
STEP_Y = int(round(1040 * scale))
TRANSITION_DURATION = float(config.get("transition_duration", 0.6))
NUM_FRAMES = max(2, int(round(TRANSITION_DURATION * FPS)))

for i in range(len(SCENES) - 1):
    sc_curr = SCENES[i]
    sc_next = SCENES[i + 1]
    print(f"Generating Transition {i+1} -> {i+2} ({sc_curr['title']} -> {sc_next['title']})...")

    last_f_path = f"{tmp_dir}/last_{i}.png"
    first_f_path = f"{tmp_dir}/first_{i+1}.png"
    subprocess.run(['ffmpeg', '-y', '-sseof', '-0.1', '-i', sc_curr['video'], '-vframes', '1', last_f_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    subprocess.run(['ffmpeg', '-y', '-ss', '0', '-i', sc_next['video'], '-vframes', '1', first_f_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    scr_0 = Image.open(last_f_path).resize((SC_VID_W, SC_VID_H), Image.Resampling.LANCZOS)
    scr_1 = Image.open(first_f_path).resize((SC_VID_W, SC_VID_H), Image.Resampling.LANCZOS)

    phone_0 = make_phone_unit(scr_0)
    phone_1 = make_phone_unit(scr_1)

    trans_frame_dir = f"{tmp_dir}/trans_frames_{i}"
    os.makedirs(trans_frame_dir, exist_ok=True)

    for f in range(NUM_FRAMES):
        t = f / float(NUM_FRAMES - 1)
        ease = 1.0 - pow(1.0 - t, 3)

        # Subtle Camera Parallax for Background
        current_y_shift = (i + ease) * PARALLAX_STEP
        bg = get_base_bg(current_y_shift)

        # Phone 0 (Current moving up)
        y0 = PH_Y - int(round(ease * STEP_Y))
        scale0 = 1.0 - ease * 0.08
        w0 = int(round(PH_W * scale0))
        h0 = int(round(PH_H * scale0))
        x0 = PH_X + (PH_W - w0) // 2
        p0 = phone_0.resize((w0, h0), Image.Resampling.BILINEAR)
        if y0 + h0 > 0 and y0 < CH:
            bg.paste(p0, (x0, y0), p0)

        # Phone 1 (Next moving up into center)
        y1 = PH_Y + STEP_Y - int(round(ease * STEP_Y))
        scale1 = 0.92 + ease * 0.08
        w1 = int(round(PH_W * scale1))
        h1 = int(round(PH_H * scale1))
        x1 = PH_X + (PH_W - w1) // 2
        p1 = phone_1.resize((w1, h1), Image.Resampling.BILINEAR)
        if y1 + h1 > 0 and y1 < CH:
            bg.paste(p1, (x1, y1), p1)

        # Typography Layer
        text_layer = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
        draw = ImageDraw.Draw(text_layer)

        # Text 0 (Fade out + slide up)
        a0 = int(round(255 * (1.0 - ease)))
        dy0 = int(round(ease * slide_dy_max))
        if a0 > 5:
            draw.text((text_x, text_y - dy0), sc_curr['title'], font=font_title, fill=(255, 255, 255, a0))
            draw.text((text_x, text_y + sub_offset_y - dy0), sc_curr['sub'], font=font_sub, fill=(255, 255, 255, int(a0 * 0.88)))

        # Text 1 (Fade in + slide in from bottom)
        a1 = int(round(255 * ease))
        dy1 = int(round((1.0 - ease) * slide_dy_max))
        if a1 > 5:
            draw.text((text_x, text_y + dy1), sc_next['title'], font=font_title, fill=(255, 255, 255, a1))
            draw.text((text_x, text_y + sub_offset_y + dy1), sc_next['sub'], font=font_sub, fill=(255, 255, 255, int(a1 * 0.88)))

        bg.paste(text_layer, (0, 0), text_layer)
        bg.save(f"{trans_frame_dir}/frame_{f:03d}.png")

    trans_mp4 = f"{tmp_dir}/trans_{i}.mp4"
    cmd_enc = [
        'ffmpeg', '-y',
        '-framerate', str(FPS),
        '-i', f"{trans_frame_dir}/frame_%03d.png",
        '-an',
    ] + enc_params + [
        '-pix_fmt', 'yuv420p',
        '-r', str(FPS),
        trans_mp4
    ]
    subprocess.run(cmd_enc, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    trans_files.append(trans_mp4)

# 6. Interleave segments and transitions
full_sequence = []
for i in range(len(SCENES)):
    full_sequence.append(seg_files[i])
    if i < len(trans_files):
        full_sequence.append(trans_files[i])

concat_list_path = f"{tmp_dir}/concat_list.txt"
with open(concat_list_path, 'w') as f:
    for fpath in full_sequence:
        f.write(f"file '{fpath}'\n")

out_video = os.path.join(BASE_DIR, config.get('output', 'zafiro_showcase_4x3.mp4'))
print("Concatenating all segments into final showcase video...")
concat_cmd = [
    'ffmpeg', '-y',
    '-f', 'concat', '-safe', '0',
    '-i', concat_list_path,
    '-c', 'copy',
    '-movflags', '+faststart',
    out_video
]
subprocess.run(concat_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

dur_final = float(json.loads(subprocess.check_output([
    'ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', out_video
]))['format']['duration'])
sz_mb = os.path.getsize(out_video) / (1024 * 1024)

print(f"\nSUCCESS! Rendered final video in {time.time() - t_start:.2f}s")
print(f"File: {out_video}")
print(f"Resolution: {CW}x{CH} @ {FPS} fps")
print(f"Duration: {dur_final:.2f}s (~{dur_final/60:.1f} min)")
print(f"Size: {sz_mb:.2f} MB")
