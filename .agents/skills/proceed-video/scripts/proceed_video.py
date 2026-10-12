#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
proceed_video.py
----------------
UI 交互动效视频分析处理工具：
1. 生成 4x4（可自定义行/列）网格切片图（Filmstrip Grid），用于横向对比各帧的加速度与物理回弹
2. 生成左右对比残影图（Side-by-Side Ghosting）：
   - 左侧：清晰静态基准帧（真实原貌）
   - 右侧：高连贯性、越早越淡、边缘清晰的历史残影图（展现运动轨迹与物理曲线）
"""

import os
import sys
import argparse
import subprocess
import tempfile
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

def run_cmd(cmd):
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(cmd)}\nError: {result.stderr}")
    return result.stdout

def get_video_duration(video_path):
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        video_path
    ]
    out = run_cmd(cmd).strip()
    return float(out)

def extract_frames(video_path, tmp_dir, fps=30, start=None, duration=None):
    cmd = ["ffmpeg", "-y"]
    if start is not None and start > 0:
        cmd.extend(["-ss", f"{start:.3f}"])
    if duration is not None and duration > 0:
        cmd.extend(["-t", f"{duration:.3f}"])
    cmd.extend([
        "-i", video_path,
        "-vf", f"fps={fps}",
        os.path.join(tmp_dir, "frame_%05d.png")
    ])
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    frames = sorted([
        os.path.join(tmp_dir, f) for f in os.listdir(tmp_dir)
        if f.startswith("frame_") and f.endswith(".png")
    ])
    return frames

def detect_motion_window(frame_paths, fps=30, threshold=2.0):
    """
    通过帧差分自动定位动效发生的起止核心时间窗口
    """
    if len(frame_paths) < 2:
        return 0.0, 1.0

    diffs = []
    base_arr = np.array(Image.open(frame_paths[0]).convert("L"), dtype=float)
    for i in range(1, len(frame_paths)):
        curr_arr = np.array(Image.open(frame_paths[i]).convert("L"), dtype=float)
        d = np.mean(np.abs(curr_arr - base_arr))
        diffs.append((i, d))

    # 寻找运动活跃区间
    active_indices = [idx for idx, d in diffs if d > threshold]
    if not active_indices:
        return 0.0, len(frame_paths) / fps

    start_idx = max(0, active_indices[0] - int(fps * 0.15))
    end_idx = min(len(frame_paths) - 1, active_indices[-1] + int(fps * 0.2))

    start_sec = start_idx / fps
    dur_sec = max(0.4, (end_idx - start_idx + 1) / fps)
    return start_sec, dur_sec

def create_grid(frame_paths, output_path, rows=4, cols=4, title="Filmstrip Grid"):
    total_needed = rows * cols
    if len(frame_paths) == 0:
        raise ValueError("No frames available to create grid")

    # 均匀采样
    indices = np.linspace(0, len(frame_paths) - 1, total_needed, dtype=int)
    sampled = [frame_paths[i] for i in indices]

    imgs = [Image.open(p) for p in sampled]
    w, h = imgs[0].size

    scale = 0.85 if total_needed <= 12 else 0.70
    tw, th = int(w * scale), int(h * scale)
    pad = 6
    top_bar = 34
    grid_w = cols * tw + (cols + 1) * pad
    grid_h = rows * th + (rows + 1) * pad + top_bar

    canvas = Image.new("RGB", (grid_w, grid_h), (22, 22, 26))
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 9), title, fill=(230, 230, 230))

    for i, im in enumerate(imgs):
        r, c = i // cols, i % cols
        x = pad + c * (tw + pad)
        y = top_bar + pad + r * (th + pad)
        canvas.paste(im.resize((tw, th), Image.Resampling.LANCZOS), (x, y))
        label = f"#{i+1:02d}"
        draw.rectangle([(x + 2, y + 2), (x + 44, y + 17)], fill=(0, 0, 0, 190))
        draw.text((x + 5, y + 3), label, fill=(255, 255, 255))

    canvas.save(output_path)
    return output_path

def create_ghost_sbs(frame_paths, output_path, num_steps=8, title="Side-by-Side: Clean vs Ghosting Trail"):
    """
    生成高保真左右对比残影图：
    - 左边：完全清晰的静态基准帧（终帧）
    - 右边：历史轨迹分层叠加，越早越淡，保留清晰结构与边界，背景不污染
    """
    if len(frame_paths) < 2:
        raise ValueError("At least 2 frames required for ghosting image")

    # 均匀选取步长
    indices = np.linspace(0, len(frame_paths) - 1, min(num_steps, len(frame_paths)), dtype=int)
    frames_rgb = [np.array(Image.open(frame_paths[i]).convert("RGB"), dtype=np.float32) for i in indices]
    clean_final = Image.open(frame_paths[indices[-1]]).convert("RGB")
    w, h = clean_final.size

    base_frame = frames_rgb[0]
    final_frame = frames_rgb[-1]

    # 计算历史图层的不透明度（越早越淡，线性递增）
    alphas = np.linspace(0.15, 0.85, len(frames_rgb) - 1)

    # 以最终帧为底，保证画面主体和背景 100% 清晰
    canvas = final_frame.copy()

    # 提取最终帧的运动区域蒙版
    final_diff = np.max(np.abs(final_frame - base_frame), axis=2)
    final_mask = (final_diff > 16)

    # 从早到晚逐层绘制历史残影
    for idx, (f_arr, a) in enumerate(zip(frames_rgb[:-1], alphas)):
        diff_from_base = np.max(np.abs(f_arr - base_frame), axis=2)
        diff_from_final = np.max(np.abs(f_arr - final_frame), axis=2)
        
        # 只提取有运动且与终帧不同的区域
        motion_mask = (diff_from_base > 16) & (diff_from_final > 12)
        
        # 外部未覆盖区域：直接按透明度 a 融合
        outer_region = motion_mask & (~final_mask)
        canvas[outer_region] = canvas[outer_region] * (1.0 - a) + f_arr[outer_region] * a
        
        # 内部重叠区域：提取边缘轮廓进行线框投影，保证内部位移阶梯清晰可见
        m_im = Image.fromarray((motion_mask.astype(np.uint8) * 255))
        edge_im = m_im.filter(ImageFilter.FIND_EDGES)
        edge_mask = (np.array(edge_im) > 40) & final_mask
        
        # 用微暗色线条印刻位移边界
        stroke_color = np.array([45.0, 45.0, 45.0])
        canvas[edge_mask] = canvas[edge_mask] * (1.0 - a * 0.7) + stroke_color * (a * 0.7)

    ghost_img = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8))

    pad = 12
    top_bar = 44
    sbs_w = w * 2 + pad * 3
    sbs_h = h + top_bar + pad

    sbs = Image.new("RGB", (sbs_w, sbs_h), (20, 20, 24))
    draw = ImageDraw.Draw(sbs)
    draw.text((pad, 12), title, fill=(240, 240, 240))
    sbs.paste(clean_final, (pad, top_bar))
    sbs.paste(ghost_img, (w + pad * 2, top_bar))

    # 标签
    draw.rectangle([(pad + 6, top_bar + 6), (pad + 170, top_bar + 26)], fill=(0, 0, 0, 200))
    draw.text((pad + 10, top_bar + 8), "Normal Clean Frame", fill=(255, 255, 255))
    draw.rectangle([(w + pad * 2 + 6, top_bar + 6), (w + pad * 2 + 250, top_bar + 26)], fill=(0, 0, 0, 200))
    draw.text((w + pad * 2 + 10, top_bar + 8), "Sharp Continuity Ghosting", fill=(255, 255, 255))

    sbs.save(output_path)
    return output_path

def main():
    parser = argparse.ArgumentParser(description="UI 交互动效视频处理工具 (proceed_video)")
    parser.add_argument("video", help="输入原子动效视频路径 (mp4/mov/webm)")
    parser.add_argument("-o", "--output-dir", default="./output", help="产物输出目录 (默认: ./output)")
    parser.add_argument("-s", "--start", type=float, default=None, help="动效开始时间秒数 (如不指定则自动运动检测)")
    parser.add_argument("-d", "--duration", type=float, default=None, help="动效持续时间秒数")
    parser.add_argument("--fps", type=int, default=30, help="抽帧帧率 (默认: 30)")
    parser.add_argument("--rows", type=int, default=4, help="切片网格行数 (默认: 4)")
    parser.add_argument("--cols", type=int, default=4, help="切片网格列数 (默认: 4)")
    parser.add_argument("--steps", type=int, default=8, help="残影轨迹阶梯数 (默认: 8)")

    args = parser.parse_args()

    if not os.path.isfile(args.video):
        print(f"Error: 视频文件不存在: {args.video}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.output_dir, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp_dir:
        # 如果未指定起止时间，先粗提取一次全视频以检测运动区间
        if args.start is None or args.duration is None:
            print("Detecting motion window...")
            preview_frames = extract_frames(args.video, tmp_dir, fps=15)
            auto_start, auto_dur = detect_motion_window(preview_frames, fps=15)
            start = args.start if args.start is not None else auto_start
            duration = args.duration if args.duration is not None else auto_dur
            print(f"Auto detected window: start={start:.2f}s, duration={duration:.2f}s")
        else:
            start = args.start
            duration = args.duration

        # 针对目标动效区间进行高帧率提取
        anim_tmp = os.path.join(tmp_dir, "anim_frames")
        os.makedirs(anim_tmp, exist_ok=True)
        frames = extract_frames(args.video, anim_tmp, fps=args.fps, start=start, duration=duration)

        if not frames:
            print("Error: 未能在指定区间提取到任何视频帧", file=sys.stderr)
            sys.exit(1)

        # 产物 1: 网格切片图
        grid_out = os.path.join(args.output_dir, "grid_slice.png")
        create_grid(frames, grid_out, rows=args.rows, cols=args.cols,
                    title=f"Filmstrip Grid ({args.rows}x{args.cols} @ {args.fps}fps, {start:.2f}s~{start+duration:.2f}s)")
        print(f"Generated Grid Slice: {grid_out}")

        # 产物 2: 左右对比残影图
        ghost_out = os.path.join(args.output_dir, "ghost_sbs.png")
        create_ghost_sbs(frames, ghost_out, num_steps=args.steps,
                         title=f"Side-by-Side: Clean vs Ghosting Trail ({args.steps} Steps)")
        print(f"Generated Ghost Side-by-Side: {ghost_out}")

    print("All artifacts generated successfully.")

if __name__ == "__main__":
    main()
