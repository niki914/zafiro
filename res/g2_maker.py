#!/usr/bin/env python3
"""
G2 Continuity Image Corner Clipper / Maker
Based on Kyant0/Capsule G2 continuous rounded rectangle algorithm.

Outputs an image (PNG with RGBA transparency or JPEG with solid background)
with G2 continuous rounded corners.
"""

import argparse
import math
import sys
from typing import List, Tuple
from PIL import Image, ImageDraw

def lerp(start: float, stop: float, fraction: float) -> float:
    return start + (stop - start) * fraction

def coerce_in(value: float, min_val: float, max_val: float) -> float:
    return max(min_val, min(value, max_val))

def coerce_at_least(value: float, min_val: float) -> float:
    return max(value, min_val)

class Point:
    __slots__ = ('x', 'y')

    def __init__(self, x: float, y: float):
        self.x = float(x)
        self.y = float(y)

    def __add__(self, other: 'Point') -> 'Point':
        return Point(self.x + other.x, self.y + other.y)

    def __sub__(self, other: 'Point') -> 'Point':
        return Point(self.x - other.x, self.y - other.y)

    def __mul__(self, scalar: float) -> 'Point':
        return Point(self.x * scalar, self.y * scalar)

    def __truediv__(self, scalar: float) -> 'Point':
        return Point(self.x / scalar, self.y / scalar)

    def __repr__(self):
        return f"Point({self.x}, {self.y})"

class CubicBezier:
    __slots__ = ('p0', 'p1', 'p2', 'p3')

    def __init__(self, p0: Point, p1: Point, p2: Point, p3: Point):
        self.p0 = p0
        self.p1 = p1
        self.p2 = p2
        self.p3 = p3

    def __mul__(self, scalar: float) -> 'CubicBezier':
        return CubicBezier(self.p0 * scalar, self.p1 * scalar, self.p2 * scalar, self.p3 * scalar)

    def sample(self, t: float) -> Point:
        u = 1.0 - t
        tt = t * t
        uu = u * u
        uuu = uu * u
        ttt = tt * t
        x = uuu * self.p0.x + 3 * uu * t * self.p1.x + 3 * u * tt * self.p2.x + ttt * self.p3.x
        y = uuu * self.p0.y + 3 * uu * t * self.p1.y + 3 * u * tt * self.p2.y + ttt * self.p3.y
        return Point(x, y)

def generate_g2_continuous_bezier_with_zero_start_curvature(
    start: Point,
    end: Point,
    start_tangent: Point,
    end_tangent: Point,
    end_curvature: float
) -> CubicBezier:
    a2 = 1.5 * end_curvature
    b = start_tangent.x * end_tangent.y - start_tangent.y * end_tangent.x
    dx = end.x - start.x
    dy = end.y - start.y
    c1 = -dy * start_tangent.x + dx * start_tangent.y
    c2 = dy * end_tangent.x - dx * end_tangent.y

    lambda0 = -c2 / b - a2 * c1 * c1 / (b * b * b)
    lambda3 = -c1 / b

    p0 = start
    p1 = start + Point(
        coerce_at_least(lambda0 * start_tangent.x, 0.0),
        coerce_at_least(lambda0 * start_tangent.y, 0.0)
    )
    p2 = end - Point(
        coerce_at_least(lambda3 * end_tangent.x, 0.0),
        coerce_at_least(lambda3 * end_tangent.y, 0.0)
    )
    p3 = end

    return CubicBezier(p0, p1, p2, p3)

class G2ContinuityProfile:
    def __init__(
        self,
        extended_fraction: float,
        arc_fraction: float,
        bezier_curvature_scale: float,
        arc_curvature_scale: float
    ):
        self.extended_fraction = float(extended_fraction)
        self.arc_fraction = float(arc_fraction)
        self.bezier_curvature_scale = float(bezier_curvature_scale)
        self.arc_curvature_scale = float(arc_curvature_scale)
        self._bezier = None

    @property
    def bezier(self) -> CubicBezier:
        if self._bezier is None:
            self._bezier = self._create_base_bezier()
        return self._bezier

    def _create_base_bezier(self) -> CubicBezier:
        arc_radians = math.pi * 0.5 * self.arc_fraction
        bezier_radians = (math.pi * 0.5 - arc_radians) * 0.5
        sin_val = math.sin(bezier_radians)
        cos_val = math.cos(bezier_radians)

        if self.bezier_curvature_scale == 1.0 and self.arc_curvature_scale == 1.0:
            half_tan = sin_val / (1.0 + cos_val)
            return CubicBezier(
                Point(-self.extended_fraction, 0.0),
                Point((1.0 - 1.5 / (1.0 + cos_val)) * half_tan, 0.0),
                Point(half_tan, 0.0),
                Point(sin_val, 1.0 - cos_val)
            )
        else:
            radius_scale = 1.0 / self.arc_curvature_scale
            arc_center = Point(0.0, 1.0) + Point(1.0 / math.sqrt(2.0), -1.0 / math.sqrt(2.0)) * (1.0 - radius_scale)
            arc_start_point = arc_center + Point(sin_val, -cos_val) * radius_scale
            return generate_g2_continuous_bezier_with_zero_start_curvature(
                start=Point(-self.extended_fraction, 0.0),
                end=arc_start_point,
                start_tangent=Point(1.0, 0.0),
                end_tangent=Point(cos_val, sin_val),
                end_curvature=self.bezier_curvature_scale
            )

# Default Profiles from Kyant0/Capsule
ProfileRoundedRectangle = G2ContinuityProfile(
    extended_fraction=0.5286651,
    arc_fraction=5.0 / 9.0,
    bezier_curvature_scale=1.0732051,
    arc_curvature_scale=1.0732051
)

ProfileCapsule = G2ContinuityProfile(
    extended_fraction=0.5286651 * 0.75,
    arc_fraction=0.0,
    bezier_curvature_scale=1.0,
    arc_curvature_scale=1.0
)

class PathCollector:
    def __init__(self, steps_per_curve: int = 40):
        self.points: List[Tuple[float, float]] = []
        self.steps_per_curve = steps_per_curve
        self.current_point = Point(0.0, 0.0)

    def move_to(self, x: float, y: float):
        self.current_point = Point(x, y)
        self.points.append((x, y))

    def line_to(self, x: float, y: float):
        self.current_point = Point(x, y)
        self.points.append((x, y))

    def cubic_to(self, x1: float, y1: float, x2: float, y2: float, x3: float, y3: float):
        bezier = CubicBezier(self.current_point, Point(x1, y1), Point(x2, y2), Point(x3, y3))
        for step in range(1, self.steps_per_curve + 1):
            t = step / self.steps_per_curve
            p = bezier.sample(t)
            self.points.append((p.x, p.y))
        self.current_point = Point(x3, y3)

    def arc_to(self, center: Point, radius: float, start_angle: float, sweep_angle: float):
        steps = max(4, int(abs(sweep_angle) / (math.pi / 2.0) * self.steps_per_curve))
        for step in range(1, steps + 1):
            t = step / steps
            ang = start_angle + sweep_angle * t
            px = center.x + radius * math.cos(ang)
            py = center.y + radius * math.sin(ang)
            self.points.append((px, py))
        self.current_point = Point(self.points[-1][0], self.points[-1][1])

    def arc_to_with_scaled_radius(
        self,
        center: Point,
        radius: float,
        radius_scale: float,
        start_angle: float,
        sweep_angle: float
    ):
        center_angle = start_angle + sweep_angle * 0.5
        scaled_center = center + Point(math.cos(center_angle), math.sin(center_angle)) * radius * (1.0 - radius_scale)
        self.arc_to(
            center=scaled_center,
            radius=radius * radius_scale,
            start_angle=start_angle,
            sweep_angle=sweep_angle
        )

def generate_g2_path(
    width: float,
    height: float,
    top_left: float,
    top_right: float,
    bottom_right: float,
    bottom_left: float,
    profile: G2ContinuityProfile = ProfileRoundedRectangle,
    capsule_profile: G2ContinuityProfile = ProfileCapsule,
    steps_per_curve: int = 40
) -> List[Tuple[float, float]]:
    """
    Implements Kyant0/Capsule G2Continuity createRoundedRectanglePathSegments.
    """
    max_radius = min(width, height) * 0.5
    top_left = coerce_in(top_left, 0.0, max_radius)
    top_right = coerce_in(top_right, 0.0, max_radius)
    bottom_right = coerce_in(bottom_right, 0.0, max_radius)
    bottom_left = coerce_in(bottom_left, 0.0, max_radius)

    # Check capsule condition
    is_capsule = (
        (top_left + top_right == width or top_left + bottom_left == height) and
        (top_left == top_right and bottom_right == bottom_left and top_left == bottom_right)
    )

    collector = PathCollector(steps_per_curve=steps_per_curve)

    if is_capsule:
        if width > height:
            # Horizontal Capsule
            radius = height * 0.5
            center_x = width * 0.5
            ratio_h = coerce_in(((center_x / radius - 1.0) / capsule_profile.extended_fraction), 0.0, 1.0)
            ext_frac = capsule_profile.extended_fraction
            ext_frac_h = ext_frac * ratio_h
            offset_h = -radius * ext_frac_h
            bez_k_scale_h = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_h)
            arc_frac = capsule_profile.arc_fraction
            bezier_h = G2ContinuityProfile(
                extended_fraction=ext_frac_h,
                arc_fraction=arc_frac,
                bezier_curvature_scale=bez_k_scale_h,
                arc_curvature_scale=1.0
            ).bezier * radius

            arc_rad = math.pi * 0.5 * arc_frac
            bez_rad = (math.pi * 0.5 - arc_rad) * 0.5
            sweep_rad = (bez_rad + arc_rad) * 2.0

            x = 0.0
            y = radius
            collector.move_to(x, y)
            collector.arc_to(
                center=Point(radius, radius),
                radius=radius,
                start_angle=math.pi * 0.5 + bez_rad,
                sweep_angle=sweep_rad
            )
            x = radius
            y = 0.0
            collector.cubic_to(
                x - bezier_h.p2.x, y + bezier_h.p2.y,
                x - bezier_h.p1.x, y + bezier_h.p1.y,
                x - coerce_at_least(bezier_h.p0.x, offset_h), y + bezier_h.p0.y
            )
            x = width - radius
            y = 0.0
            collector.line_to(x + offset_h, y)
            collector.cubic_to(
                x + bezier_h.p1.x, y + bezier_h.p1.y,
                x + bezier_h.p2.x, y + bezier_h.p2.y,
                x + bezier_h.p3.x, y + bezier_h.p3.y
            )
            collector.arc_to(
                center=Point(width - radius, radius),
                radius=radius,
                start_angle=-(math.pi * 0.5 - bez_rad),
                sweep_angle=sweep_rad
            )
            x = width - radius
            y = height
            collector.cubic_to(
                x + bezier_h.p2.x, y - bezier_h.p2.y,
                x + bezier_h.p1.x, y - bezier_h.p1.y,
                x + coerce_at_least(bezier_h.p0.x, offset_h), y - bezier_h.p0.y
            )
            x = radius
            y = height
            collector.line_to(x - offset_h, y)
            collector.cubic_to(
                x - bezier_h.p1.x, y - bezier_h.p1.y,
                x - bezier_h.p2.x, y - bezier_h.p2.y,
                x - bezier_h.p3.x, y - bezier_h.p3.y
            )
            return collector.points

        elif width < height:
            # Vertical Capsule
            radius = width * 0.5
            center_y = height * 0.5
            ratio_v = coerce_in(((center_y / radius - 1.0) / capsule_profile.extended_fraction), 0.0, 1.0)
            ext_frac = capsule_profile.extended_fraction
            ext_frac_v = ext_frac * ratio_v
            offset_v = -radius * ext_frac_v
            bez_k_scale_v = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_v)
            arc_frac = capsule_profile.arc_fraction
            bezier_v = G2ContinuityProfile(
                extended_fraction=ext_frac_v,
                arc_fraction=arc_frac,
                bezier_curvature_scale=bez_k_scale_v,
                arc_curvature_scale=1.0
            ).bezier * radius

            arc_rad = math.pi * 0.5 * arc_frac
            bez_rad = (math.pi * 0.5 - arc_rad) * 0.5
            sweep_rad = (bez_rad + arc_rad) * 2.0

            x = 0.0
            y = radius
            collector.move_to(x, y - offset_v)
            collector.cubic_to(
                x + bezier_v.p1.y, y - bezier_v.p1.x,
                x + bezier_v.p2.y, y - bezier_v.p2.x,
                x + bezier_v.p3.y, y - bezier_v.p3.x
            )
            collector.arc_to(
                center=Point(radius, radius),
                radius=radius,
                start_angle=-(math.pi - bez_rad),
                sweep_angle=sweep_rad
            )
            x = width
            y = radius
            collector.cubic_to(
                x - bezier_v.p2.y, y - bezier_v.p2.x,
                x - bezier_v.p1.y, y - bezier_v.p1.x,
                x - bezier_v.p0.y, y - coerce_at_least(bezier_v.p0.x, offset_v)
            )
            x = width
            y = height - radius
            collector.line_to(x, y + offset_v)
            collector.cubic_to(
                x - bezier_v.p1.y, y + bezier_v.p1.x,
                x - bezier_v.p2.y, y + bezier_v.p2.x,
                x - bezier_v.p3.y, y + bezier_v.p3.x
            )
            collector.arc_to(
                center=Point(width - radius, height - radius),
                radius=radius,
                start_angle=bez_rad,
                sweep_angle=sweep_rad
            )
            x = 0.0
            y = height - radius
            collector.cubic_to(
                x + bezier_v.p2.y, y + bezier_v.p2.x,
                x + bezier_v.p1.y, y + bezier_v.p1.x,
                x + bezier_v.p0.y, y + coerce_at_least(bezier_v.p0.x, offset_v)
            )
            return collector.points
        else:
            # Circle
            radius = width * 0.5
            collector.move_to(radius * 2, radius)
            collector.arc_to(Point(radius, radius), radius, 0.0, 2 * math.pi)
            return collector.points

    # Standard Rounded Rectangle
    center_x = width * 0.5
    center_y = height * 0.5

    def get_ratio(axis_len: float, r: float) -> float:
        if r <= 0.0:
            return 1.0
        return coerce_in((axis_len / r - 1.0) / profile.extended_fraction, 0.0, 1.0)

    ratio_tl_v = get_ratio(center_y, top_left)
    ratio_tl_h = get_ratio(center_x, top_left)
    ratio_tr_h = get_ratio(center_x, top_right)
    ratio_tr_v = get_ratio(center_y, top_right)
    ratio_br_v = get_ratio(center_y, bottom_right)
    ratio_br_h = get_ratio(center_x, bottom_right)
    ratio_bl_h = get_ratio(center_x, bottom_left)
    ratio_bl_v = get_ratio(center_y, bottom_left)

    ratio_tl = min(ratio_tl_v, ratio_tl_h)
    ratio_tr = min(ratio_tr_h, ratio_tr_v)
    ratio_br = min(ratio_br_v, ratio_br_h)
    ratio_bl = min(ratio_bl_h, ratio_bl_v)

    ext_frac_tl = lerp(capsule_profile.extended_fraction, profile.extended_fraction, ratio_tl)
    ext_frac_tr = lerp(capsule_profile.extended_fraction, profile.extended_fraction, ratio_tr)
    ext_frac_br = lerp(capsule_profile.extended_fraction, profile.extended_fraction, ratio_br)
    ext_frac_bl = lerp(capsule_profile.extended_fraction, profile.extended_fraction, ratio_bl)

    ext_frac_tl_v = ext_frac_tl * ratio_tl_v
    ext_frac_tl_h = ext_frac_tl * ratio_tl_h
    ext_frac_tr_h = ext_frac_tr * ratio_tr_h
    ext_frac_tr_v = ext_frac_tr * ratio_tr_v
    ext_frac_br_v = ext_frac_br * ratio_br_v
    ext_frac_br_h = ext_frac_br * ratio_br_h
    ext_frac_bl_h = ext_frac_bl * ratio_bl_h
    ext_frac_bl_v = ext_frac_bl * ratio_bl_v

    offset_tl_v = -top_left * ext_frac_tl_v
    offset_tl_h = -top_left * ext_frac_tl_h
    offset_tr_h = -top_right * ext_frac_tr_h
    offset_tr_v = -top_right * ext_frac_tr_v
    offset_br_v = -bottom_right * ext_frac_br_v
    offset_br_h = -bottom_right * ext_frac_br_h
    offset_bl_h = -bottom_left * ext_frac_bl_h
    offset_bl_v = -bottom_left * ext_frac_bl_v

    bez_k_scale_tl_v = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_tl_v)
    bez_k_scale_tl_h = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_tl_h)
    bez_k_scale_tr_h = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_tr_h)
    bez_k_scale_tr_v = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_tr_v)
    bez_k_scale_br_v = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_br_v)
    bez_k_scale_br_h = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_br_h)
    bez_k_scale_bl_h = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_bl_h)
    bez_k_scale_bl_v = lerp(capsule_profile.bezier_curvature_scale, profile.bezier_curvature_scale, ratio_bl_v)

    arc_frac_tl = lerp(capsule_profile.arc_fraction, profile.arc_fraction, ratio_tl)
    arc_frac_tr = lerp(capsule_profile.arc_fraction, profile.arc_fraction, ratio_tr)
    arc_frac_br = lerp(capsule_profile.arc_fraction, profile.arc_fraction, ratio_br)
    arc_frac_bl = lerp(capsule_profile.arc_fraction, profile.arc_fraction, ratio_bl)

    arc_k_scale_tl = 1.0 + (profile.arc_curvature_scale - 1.0) * ratio_tl
    arc_k_scale_tr = 1.0 + (profile.arc_curvature_scale - 1.0) * ratio_tr
    arc_k_scale_br = 1.0 + (profile.arc_curvature_scale - 1.0) * ratio_br
    arc_k_scale_bl = 1.0 + (profile.arc_curvature_scale - 1.0) * ratio_bl

    def resolve_bez(ext_f: float, arc_f: float, bez_k: float, arc_k: float) -> CubicBezier:
        return G2ContinuityProfile(ext_f, arc_f, bez_k, arc_k).bezier

    bezier_tl_v = resolve_bez(ext_frac_tl_v, arc_frac_tl, bez_k_scale_tl_v, arc_k_scale_tl)
    bezier_tl_h = resolve_bez(ext_frac_tl_h, arc_frac_tl, bez_k_scale_tl_h, arc_k_scale_tl)
    bezier_tr_h = resolve_bez(ext_frac_tr_h, arc_frac_tr, bez_k_scale_tr_h, arc_k_scale_tr)
    bezier_tr_v = resolve_bez(ext_frac_tr_v, arc_frac_tr, bez_k_scale_tr_v, arc_k_scale_tr)
    bezier_br_v = resolve_bez(ext_frac_br_v, arc_frac_br, bez_k_scale_br_v, arc_k_scale_br)
    bezier_br_h = resolve_bez(ext_frac_br_h, arc_frac_br, bez_k_scale_br_h, arc_k_scale_br)
    bezier_bl_h = resolve_bez(ext_frac_bl_h, arc_frac_bl, bez_k_scale_bl_h, arc_k_scale_bl)
    bezier_bl_v = resolve_bez(ext_frac_bl_v, arc_frac_bl, bez_k_scale_bl_v, arc_k_scale_bl)

    x = 0.0
    y = top_left
    collector.move_to(x, y - offset_tl_v)

    # TL
    if top_left > 0.0:
        # TLV
        collector.cubic_to(
            x + bezier_tl_v.p1.y * top_left, y - bezier_tl_v.p1.x * top_left,
            x + bezier_tl_v.p2.y * top_left, y - bezier_tl_v.p2.x * top_left,
            x + bezier_tl_v.p3.y * top_left, y - bezier_tl_v.p3.x * top_left
        )
        # TLC
        collector.arc_to_with_scaled_radius(
            center=Point(top_left, top_left),
            radius=top_left,
            radius_scale=1.0 / arc_k_scale_tl,
            start_angle=math.pi + math.pi * 0.5 * (1.0 - arc_frac_tl) * 0.5,
            sweep_angle=math.pi * 0.5 * arc_frac_tl
        )
        # TLH
        x = top_left
        y = 0.0
        collector.cubic_to(
            x - bezier_tl_h.p2.x * top_left, y + bezier_tl_h.p2.y * top_left,
            x - bezier_tl_h.p1.x * top_left, y + bezier_tl_h.p1.y * top_left,
            x - coerce_at_least(bezier_tl_h.p0.x * top_left, offset_tl_h), y + bezier_tl_h.p0.y * top_left
        )

    # TI
    x = width - top_right
    y = 0.0
    collector.line_to(x + offset_tr_h, y)

    # TR
    if top_right > 0.0:
        # TRH
        collector.cubic_to(
            x + bezier_tr_h.p1.x * top_right, y + bezier_tr_h.p1.y * top_right,
            x + bezier_tr_h.p2.x * top_right, y + bezier_tr_h.p2.y * top_right,
            x + bezier_tr_h.p3.x * top_right, y + bezier_tr_h.p3.y * top_right
        )
        # TRC
        collector.arc_to_with_scaled_radius(
            center=Point(width - top_right, top_right),
            radius=top_right,
            radius_scale=1.0 / arc_k_scale_tr,
            start_angle=-math.pi * 0.5 + math.pi * 0.5 * (1.0 - arc_frac_tr) * 0.5,
            sweep_angle=math.pi * 0.5 * arc_frac_tr
        )
        # TRV
        x = width
        y = top_right
        collector.cubic_to(
            x - bezier_tr_v.p2.y * top_right, y - bezier_tr_v.p2.x * top_right,
            x - bezier_tr_v.p1.y * top_right, y - bezier_tr_v.p1.x * top_right,
            x - bezier_tr_v.p0.y * top_right, y - coerce_at_least(bezier_tr_v.p0.x * top_right, offset_tr_v)
        )

    # RI
    x = width
    y = height - bottom_right
    collector.line_to(x, y + offset_br_v)

    # BR
    if bottom_right > 0.0:
        # BRV
        collector.cubic_to(
            x - bezier_br_v.p1.y * bottom_right, y + bezier_br_v.p1.x * bottom_right,
            x - bezier_br_v.p2.y * bottom_right, y + bezier_br_v.p2.x * bottom_right,
            x - bezier_br_v.p3.y * bottom_right, y + bezier_br_v.p3.x * bottom_right
        )
        # BRC
        collector.arc_to_with_scaled_radius(
            center=Point(width - bottom_right, height - bottom_right),
            radius=bottom_right,
            radius_scale=1.0 / arc_k_scale_br,
            start_angle=0.0 + math.pi * 0.5 * (1.0 - arc_frac_br) * 0.5,
            sweep_angle=math.pi * 0.5 * arc_frac_br
        )
        # BRH
        x = width - bottom_right
        y = height
        collector.cubic_to(
            x + bezier_br_h.p2.x * bottom_right, y - bezier_br_h.p2.y * bottom_right,
            x + bezier_br_h.p1.x * bottom_right, y - bezier_br_h.p1.y * bottom_right,
            x + coerce_at_least(bezier_br_h.p0.x * bottom_right, offset_br_h), y - bezier_br_h.p0.y * bottom_right
        )

    # BI
    x = bottom_left
    y = height
    collector.line_to(x - offset_bl_h, y)

    # BL
    if bottom_left > 0.0:
        # BLH
        collector.cubic_to(
            x - bezier_bl_h.p1.x * bottom_left, y - bezier_bl_h.p1.y * bottom_left,
            x - bezier_bl_h.p2.x * bottom_left, y - bezier_bl_h.p2.y * bottom_left,
            x - bezier_bl_h.p3.x * bottom_left, y - bezier_bl_h.p3.y * bottom_left
        )
        # BLC
        collector.arc_to_with_scaled_radius(
            center=Point(bottom_left, height - bottom_left),
            radius=bottom_left,
            radius_scale=1.0 / arc_k_scale_bl,
            start_angle=math.pi * 0.5 + math.pi * 0.5 * (1.0 - arc_frac_bl) * 0.5,
            sweep_angle=math.pi * 0.5 * arc_frac_bl
        )
        # BLV
        x = 0.0
        y = height - bottom_left
        collector.cubic_to(
            x + bezier_bl_v.p2.y * bottom_left, y + bezier_bl_v.p2.x * bottom_left,
            x + bezier_bl_v.p1.y * bottom_left, y + bezier_bl_v.p1.x * bottom_left,
            x + bezier_bl_v.p0.y * bottom_left, y + coerce_at_least(bezier_bl_v.p0.x * bottom_left, offset_bl_v)
        )

    return collector.points

def apply_g2_corners(
    image: Image.Image,
    top_left: float,
    top_right: float,
    bottom_right: float,
    bottom_left: float,
    scale_factor: int = 4
) -> Image.Image:
    """
    Clips image to G2 rounded rectangle with 4 corner radii.
    Uses 4x supersampling for high-quality antialiasing.
    """
    w, h = image.size

    # Render high-resolution mask
    mask_w, mask_h = w * scale_factor, h * scale_factor
    scaled_points = generate_g2_path(
        width=mask_w,
        height=mask_h,
        top_left=top_left * scale_factor,
        top_right=top_right * scale_factor,
        bottom_right=bottom_right * scale_factor,
        bottom_left=bottom_left * scale_factor,
        steps_per_curve=40
    )

    mask = Image.new('L', (mask_w, mask_h), 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon(scaled_points, fill=255)

    # Downsample mask with high quality filter (LANCZOS or BICUBIC)
    mask = mask.resize((w, h), Image.Resampling.LANCZOS)

    # Apply mask to image
    rgba_image = image.convert('RGBA')
    r, g, b, orig_a = rgba_image.split()

    # Blend with original alpha channel if present
    final_a = Image.new('L', (w, h))
    final_a.paste(mask)
    if 'A' in image.getbands():
        import PIL.ImageChops as ImageChops
        final_a = ImageChops.multiply(orig_a, mask)

    rgba_image.putalpha(final_a)
    return rgba_image

def parse_radii(radii_str: str) -> Tuple[float, float, float, float]:
    """
    Parses corner radii string:
    - 1 value: r (all 4 corners)
    - 2 values: top, bottom
    - 4 values: tl, tr, br, bl (CSS order)
    """
    parts = [float(p.strip()) for p in radii_str.replace(',', ' ').split()]
    if len(parts) == 1:
        return parts[0], parts[0], parts[0], parts[0]
    elif len(parts) == 2:
        return parts[0], parts[0], parts[1], parts[1]
    elif len(parts) == 4:
        return parts[0], parts[1], parts[2], parts[3]
    else:
        raise ValueError("Radius must have 1, 2, or 4 values (e.g. '32' or '32 32 32 32')")

def main():
    parser = argparse.ArgumentParser(
        description="Clip an image using Capsule's G2 continuous rounded rectangle algorithm."
    )
    parser.add_argument("input", help="Input image file path")
    parser.add_argument("-o", "--output", help="Output image file path (defaults to <name>_g2.png)")
    parser.add_argument(
        "-r", "--radius",
        help="Corner radius in px. Can be 1, 2, or 4 values (CSS order: TL TR BR BL). E.g. '64' or '64 64 32 32'",
        default=None
    )
    parser.add_argument("--tl", type=float, help="Top-Left radius in px")
    parser.add_argument("--tr", type=float, help="Top-Right radius in px")
    parser.add_argument("--br", type=float, help="Bottom-Right radius in px")
    parser.add_argument("--bl", type=float, help="Bottom-Left radius in px")
    parser.add_argument("--bg", help="Background color in hex or name (e.g. #FFFFFF) if saving to non-transparent format like JPEG", default=None)
    parser.add_argument("--scale", type=int, default=4, help="Supersampling antialiasing factor (default: 4)")

    args = parser.parse_args()

    # Determine radii
    tl = tr = br = bl = 0.0
    if args.radius is not None:
        tl, tr, br, bl = parse_radii(args.radius)
    if args.tl is not None:
        tl = args.tl
    if args.tr is not None:
        tr = args.tr
    if args.br is not None:
        br = args.br
    if args.bl is not None:
        bl = args.bl

    if tl == 0.0 and tr == 0.0 and br == 0.0 and bl == 0.0:
        print("Warning: All radii are 0, defaulting radius to 1/8 of min dimension.", file=sys.stderr)
        default_r = None
    else:
        default_r = None

    try:
        img = Image.open(args.input)
    except Exception as e:
        print(f"Error opening image {args.input}: {e}", file=sys.stderr)
        sys.exit(1)

    if default_r is None and tl == 0.0 and tr == 0.0 and br == 0.0 and bl == 0.0:
        w, h = img.size
        tl = tr = br = bl = min(w, h) * 0.125

    print(f"Processing '{args.input}' ({img.width}x{img.height}) with G2 radii: TL={tl}, TR={tr}, BR={br}, BL={bl}...")
    result = apply_g2_corners(img, tl, tr, br, bl, scale_factor=args.scale)

    # Output path
    if args.output:
        out_path = args.output
    else:
        import os
        base, _ = os.path.splitext(args.input)
        out_path = f"{base}_g2.png"

    # Determine output extension and format
    is_jpeg = out_path.lower().endswith(('.jpg', '.jpeg'))
    
    if is_jpeg and not args.bg:
        # JPEG cannot store alpha channel: default to white or warn
        print("Note: JPEG does not support transparency. Auto-compositing onto white background (use --bg <color> to customize, or save as .png).", file=sys.stderr)
        bg_color = (255, 255, 255)
        bg_img = Image.new('RGB', result.size, bg_color)
        bg_img.paste(result, mask=result.split()[3])
        bg_img.save(out_path, quality=95)
    elif args.bg:
        # Composite over custom background
        bg_img = Image.new('RGB', result.size, args.bg)
        bg_img.paste(result, mask=result.split()[3])
        bg_img.save(out_path, quality=95 if is_jpeg else None)
    else:
        result.save(out_path)

    print(f"Saved G2 rounded image to: {out_path}")

if __name__ == "__main__":
    main()
