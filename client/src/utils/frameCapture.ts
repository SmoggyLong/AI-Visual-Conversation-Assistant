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
 * 帧差检测 —— 像素校验和 + 容差比对。
 *
 * 之前用哈希比对的问题：量化哈希对单像素变化过于敏感，
 * 64×64=4096 个像素中只要有一个跨了量化边界，哈希就不同。
 * 静态画面下传感器噪声导致每秒都有几个像素跨边界 → 哈希永远不同。
 *
 * 改用像素 RGB 校验和 + 1.5% 容差：
 * - 传感器噪声让校验和波动 < 1.5% → 视为无变化，不发送
 * - 物体移动、光线剧变让校验和波动 >> 1.5% → 视为有变化，发送
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
  const currentSum = pixelRgbSum(currentPixels);
  const previousSum = pixelRgbSum(previousPixels);
  // 差异超过 1.5% → 有变化
  const diff = Math.abs(currentSum - previousSum) / Math.max(1, previousSum);
  return diff > 0.015;
}

/**
 * 像素 RGB 总和（用于校验和对比）。
 */
function pixelRgbSum(pixels: ImageData): number {
  let sum = 0;
  const data = pixels.data;
  for (let i = 0; i < data.length; i += 4) {
    sum += data[i] + data[i + 1] + data[i + 2];
  }
  return sum;
}

/**
 * 像素校验和 —— 用于后端缓存去重。
 */
function pixelChecksum(pixels: ImageData): string {
  return pixelRgbSum(pixels).toString(16);
}
