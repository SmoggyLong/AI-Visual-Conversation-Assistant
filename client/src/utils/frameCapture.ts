import type { FrameDataPayload } from '../types/messages';

/**
 * 从 video 元素截取当前帧，返回完整 JPEG 帧和 64x64 缩略图像素数据。
 *
 * 帧差检测不再用哈希，改用 64x64 缩略图的像素级比对 + 变化比例阈值，
 * 解决摄像头传感器噪声导致的误判。
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

  // ===== 缩略图：64x64 用于像素对比 =====
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
 * 像素级帧差检测。
 *
 * 比较两帧 64x64 缩略图的每个像素 RGB 值，
 * 单像素任一通道差值 >10 视为"变化像素"，
 * 变化像素占比超过 threshold 才视为画面变化。
 *
 * 这样摄像头传感器噪声（微小波动）不会被误判为变化。
 *
 * @param currentPixels   当前帧的 64x64 ImageData
 * @param previousPixels  上一帧的 64x64 ImageData（首次为 null）
 * @param threshold       变化像素占比阈值，默认 0.02（2%）
 * @returns true=画面有显著变化
 */
export function hasFrameChanged(
  currentPixels: ImageData,
  previousPixels: ImageData | null,
  threshold: number = 0.02
): boolean {
  if (!previousPixels) return true; // 首帧

  const data1 = currentPixels.data;
  const data2 = previousPixels.data;
  const totalPixels = data1.length / 4;
  let diffCount = 0;

  for (let i = 0; i < data1.length; i += 4) {
    const dr = Math.abs(data1[i] - data2[i]);
    const dg = Math.abs(data1[i + 1] - data2[i + 1]);
    const db = Math.abs(data1[i + 2] - data2[i + 2]);
    if (dr > 10 || dg > 10 || db > 10) {
      diffCount++;
    }
  }

  const ratio = diffCount / totalPixels;
  return ratio > threshold;
}

/**
 * 像素校验和 —— 用于后端缓存去重（替代图片哈希）。
 */
function pixelChecksum(pixels: ImageData): string {
  let sum = 0;
  const data = pixels.data;
  for (let i = 0; i < data.length; i += 4) {
    sum += data[i] + data[i + 1] + data[i + 2];
  }
  return sum.toString(16);
}
