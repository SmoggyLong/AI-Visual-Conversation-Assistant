import type { FrameDataPayload } from '../types/messages';

/**
 * 从 video 元素截取当前帧，返回完整 JPEG 帧和 64x64 缩略图像素数据。
 */
export function captureFrame(
  video: HTMLVideoElement,
  maxWidth: number = 640
): { fullFrame: FrameDataPayload; thumbPixels: ImageData } | null {
  if (video.readyState < 2) return null;

  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (vw === 0 || vh === 0) return null;

  // ===== 完整帧：等比缩放到 maxWidth =====
  const scale = Math.min(1, maxWidth / vw);
  const fw = Math.floor(vw * scale);
  const fh = Math.floor(vh * scale);

  const fullCanvas = document.createElement('canvas');
  fullCanvas.width = fw;
  fullCanvas.height = fh;
  const fctx = fullCanvas.getContext('2d')!;
  fctx.drawImage(video, 0, 0, fw, fh);

  // Canvas → JPEG base64（quality=0.7），去掉 data:image 前缀
  const fullData = fullCanvas.toDataURL('image/jpeg', 0.7);

  // ===== 缩略图：64x64 用于帧差检测 =====
  const thumbCanvas = document.createElement('canvas');
  thumbCanvas.width = 64;
  thumbCanvas.height = 64;
  const tctx = thumbCanvas.getContext('2d')!;
  tctx.drawImage(video, 0, 0, 64, 64);
  const thumbPixels = tctx.getImageData(0, 0, 64, 64);

  return {
    fullFrame: {
      format: 'jpeg',
      width: fw,
      height: fh,
      data: fullData.split(',')[1],
      changed: false,
      imageChecksum: pixelChecksum(thumbPixels),
    },
    thumbPixels,
  };
}

/**
 * 帧差检测 —— 量化像素哈希对比。
 *
 * 问题：之前用 toDataURL('image/jpeg') 的字节计算哈希，JPEG 压缩器（DCT + Huffman）
 * 对传感器噪声（±1-3 RGB）极其敏感，微小的像素波动会导致完全不同的字节流，
 * 从而产生完全不同的哈希值，导致静态画面也被误判为"有变化"。
 *
 * 修复：直接在 64x64 的原始像素数据上计算哈希，并在计算前将 RGB 量化到 8 个级别
 * （0-31→0, 32-63→1, ..., 224-255→7）。传感器噪声 ±3 不会跨越量化边界，
 * 因此静态画面产生相同的哈希值；而真正的场景变化（物体移动、光线变化）会跨越边界，
 * 产生不同的哈希值。
 *
 * @param currentPixels   当前帧的 64x64 ImageData
 * @param previousPixels  上一帧的 64x64 ImageData（首次为 null）
 * @returns true=画面有显著变化
 */
export function hasFrameChanged(
  currentPixels: ImageData,
  previousPixels: ImageData | null
): boolean {
  if (!previousPixels) return true;
  return quantizedHash(currentPixels) !== quantizedHash(previousPixels);
}

/**
 * 对 64x64 像素数据做量化哈希。
 * RGB 每通道量化到 8 级（除以 32），滤除传感器噪声 ±15，
 * 只保留宏观的颜色变化。
 */
function quantizedHash(pixels: ImageData): number {
  const data = pixels.data;
  let hash = 0;
  for (let i = 0; i < data.length; i += 4) {
    // 量化：256 级 → 8 级（0-7），噪声 ±15 不会跨级
    const r = Math.floor(data[i] / 32);
    const g = Math.floor(data[i + 1] / 32);
    const b = Math.floor(data[i + 2] / 32);
    // 3 个 3-bit 值合并为 9-bit，混入哈希
    hash = ((hash << 5) - hash + (r << 6 | g << 3 | b)) | 0;
  }
  return hash;
}

/**
 * 像素校验和 —— 用于后端缓存去重。
 */
function pixelChecksum(pixels: ImageData): string {
  let sum = 0;
  const data = pixels.data;
  for (let i = 0; i < data.length; i += 4) {
    sum += data[i] + data[i + 1] + data[i + 2];
  }
  return sum.toString(16);
}
